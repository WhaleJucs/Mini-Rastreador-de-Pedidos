// Builds only a clean temporary copy, with synthetic inputs; never loads the developer's .env files.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const source = fileURLToPath(new URL('../', import.meta.url));
const npmCli = process.env.npm_execpath;
assert.ok(npmCli && fs.existsSync(npmCli), 'Execute com npm run verify:env.');
const temporaryRoot = fs.realpathSync(os.tmpdir());
const fixture = fs.mkdtempSync(path.join(temporaryRoot, 'foody-env-verification-'));
const environment = { ...process.env };
for (const name of Object.keys(environment)) {
  if (/^(VITE_|APP_|SPRING_|NODE_ENV$)/i.test(name)) delete environment[name];
}
const markers = {
  APP_JWT_SECRET_BASE64: Buffer.from('SYNTHETIC_ENV_T05_BACKEND_ONLY_32_BYTES').toString('base64'),
  APP_PRIVATE_MARKER: 'SYNTHETIC_ENV_T05_NOT_PUBLIC',
};
const matrix = [
  { script: 'build:dev', label: 'local', endpoint: 'http://127.0.0.1:8081/api' },
  { script: 'build:hml', label: 'hml', endpoint: 'https://hml-build.example.invalid/api' },
  { script: 'build:prod', label: 'prod', endpoint: 'https://prod-build.example.invalid/api' },
];
const inputFiles = ['package.json', 'package-lock.json', 'vite.config.js', 'index.html'];
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const lockHash = sha(fs.readFileSync(path.join(source, 'package-lock.json')));
let negativeBuilds = 0;

function run(args, values = {}, shouldPass = true) {
  const result = spawnSync(process.execPath, [npmCli, ...args], {
    cwd: fixture, env: { ...environment, ...markers, ...values },
    encoding: 'utf8', timeout: 180_000, maxBuffer: 4 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  assert.equal(result.status === 0, shouldPass, `Resultado inesperado de npm ${args.join(' ')}:\n${result.stdout}\n${result.stderr}`);
  if (!shouldPass) {
    assert.match(result.stdout + result.stderr, /VITE_APP_ENV|VITE_API_BASE_URL|NODE_ENV=production/);
    negativeBuilds++;
  }
}

function filesIn(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? filesIn(file) : [file];
  });
}

function inspectBuild(current, probe) {
  const files = filesIn(path.join(fixture, 'dist')).sort();
  assert.ok(files.some(file => file.endsWith('.map')), 'A prova deve incluir sourcemaps.');
  const content = files.map(file => fs.readFileSync(file));
  for (const bytes of content) {
    for (const marker of Object.values(markers)) {
      assert.ok(!bytes.includes(Buffer.from(marker)), 'Marcador backend encontrado no dist.');
    }
    for (const other of matrix.filter(item => item.label !== current.label)) {
      assert.ok(!bytes.includes(Buffer.from(other.endpoint)), 'Endpoint de outro contexto encontrado.');
    }
  }
  const javascript = files.filter(file => file.endsWith('.js')).map(file => fs.readFileSync(file, 'utf8')).join('\n');
  if (probe) {
    assert.ok(javascript.includes(current.endpoint), 'O cliente Axios deve usar o endpoint do build.');
    const entry = files.find(file => file.endsWith('.js'));
    // Execute only the bundled config/client probe, with a minimal modulepreload stub.
    // Inputs from this new process must not replace the public values compiled into dist.
    const executed = spawnSync(process.execPath, ['--input-type=module', '-e',
      "import { pathToFileURL } from 'node:url'; globalThis.document = { createElement: () => ({ relList: { supports: () => true } }) }; await import(pathToFileURL(process.argv[1])); console.log(JSON.stringify(globalThis.__ENV_VERIFICATION__));", entry], {
      cwd: fixture, env: { ...environment, VITE_APP_ENV: 'runtime-other', VITE_API_BASE_URL: 'https://runtime-other.example.invalid/api' },
      encoding: 'utf8', timeout: 30_000,
    });
    assert.equal(executed.status, 0, executed.stderr);
    assert.deepEqual(JSON.parse(executed.stdout), { appEnv: current.label, apiBaseUrl: current.endpoint });
  }
  return sha(Buffer.concat(content));
}

try {
  for (const file of [...inputFiles, 'src']) {
    fs.cpSync(path.join(source, file), path.join(fixture, file), { recursive: true });
  }
  run(['ci', '--no-audit', '--no-fund']);
  const main = path.join(fixture, 'src/main.jsx');
  const originalMain = fs.readFileSync(main);
  const hashes = {};
  for (const probe of [false, true]) {
    // The shell does not yet use Axios; keep the actual client/config in the probe bundle only.
    if (probe) fs.writeFileSync(main, "import api from './services/api.js'; import { env } from './config/env.js'; globalThis.__ENV_VERIFICATION__ = { ...env, apiBaseUrl: api.defaults.baseURL };\n");
    for (const current of matrix) {
      const values = { VITE_APP_ENV: current.label, VITE_API_BASE_URL: current.endpoint };
      run(['run', current.script, '--', '--sourcemap'], values);
      const hash = inspectBuild(current, probe);
      if (probe) hashes[current.label] = hash;
    }
  }
  fs.writeFileSync(main, originalMain);
  assert.notEqual(hashes.hml, hashes.prod, 'Endpoints de build distintos exigem dist distinto.');
  run(['run', 'build', '--', '--sourcemap'], {
    VITE_APP_ENV: 'prod', VITE_API_BASE_URL: matrix[2].endpoint,
  });
  inspectBuild(matrix[2], false);

  for (const current of matrix.filter(item => item.label !== 'local')) {
    const values = { VITE_APP_ENV: current.label, VITE_API_BASE_URL: current.endpoint };
    for (const override of [
      { VITE_API_BASE_URL: '' },
      { VITE_API_BASE_URL: '/api' },
      { VITE_API_BASE_URL: 'http://remote.example.invalid/api' },
      { VITE_API_BASE_URL: 'https://localhost/api' },
      { VITE_APP_ENV: 'local' },
      { NODE_ENV: 'development' },
    ]) run(['run', current.script], { ...values, ...override }, false);
  }
  // Prove the missing URL guard and NODE_ENV guard also apply to mode files.
  for (const [mode, current] of [['hml', matrix[1]], ['production', matrix[2]]]) {
    const envFile = path.join(fixture, `.env.${mode}.local`);
    try {
      fs.writeFileSync(envFile, `VITE_APP_ENV=${current.label}\n`);
      run(['run', current.script], {}, false);
      fs.appendFileSync(envFile, `VITE_API_BASE_URL=${current.endpoint}\nNODE_ENV=development\n`);
      run(['run', current.script], {}, false);
    } finally { fs.rmSync(envFile, { force: true }); }
  }
  assert.equal(sha(fs.readFileSync(path.join(fixture, 'package-lock.json'))), lockHash);
  assert.equal(sha(fs.readFileSync(path.join(source, 'package-lock.json'))), lockHash);
  console.log(JSON.stringify({ result: 'PASS', cleanInstall: true, normalBuilds: 4, probeBuilds: 3,
    sourcemapsScanned: true, backendMarkersAbsent: true, distinctHmlProd: true,
    negativeBuilds, lockSha256: lockHash, probeDistSha256: hashes }));
} finally {
  assert.equal(path.dirname(fixture), temporaryRoot);
  assert.ok(path.basename(fixture).startsWith('foody-env-verification-'));
  fs.rmSync(fixture, { recursive: true, force: true });
}
