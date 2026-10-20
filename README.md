# Mini Rastreador de Pedidos — Foody Delivery

Java 21/Spring Boot/SQLite e React/JavaScript/Vite. A interface apresenta a
fundação inicial; cadastro, login e pedidos serão implementados nos próximos
marcos. A API permanece fechada e retorna 401 sem sessão.

## Pré-requisitos

- JDK 21 (`JAVA_HOME` e `bin` no `PATH`); verificado com Temurin 21.0.12.1.
- Node.js >= 22.22.0 e npm; verificado com Node 24.18.0/npm 11.16.0.
- Acesso aos repositórios Maven/npm para baixar dependências.

Confira `java -version`, `javac -version`, `node --version` e `npm --version`.
O Maven Wrapper baixa Maven 3.9.11 com checksum; não exige Maven global.
No Windows use PowerShell 7 para os comandos abaixo. Se necessário, ajuste
somente a sessão (caminho conforme sua instalação):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Backend local — primeiro terminal, a partir da raiz

```powershell
Set-Location .\backend
.\mvnw.cmd verify
New-Item -ItemType Directory -Force -Path .\data | Out-Null
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:APP_JWT_SECRET_BASE64 = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
java -jar .\target\delivery-0.0.1-SNAPSHOT.jar
```

Esperado: `BUILD SUCCESS` e backend em `http://127.0.0.1:8081`.
O perfil `local` tem defaults de desenvolvimento: SQLite em
`backend/data/foody-local.db`, issuer `foody-local`, origem configurada
`http://127.0.0.1:5174`, bind loopback e porta 8081. Execute o JAR **dentro de
backend**: caminhos relativos partem do diretório de trabalho. O diretório pai
do banco precisa existir e ser gravável; o schema é criado sem apagar dados.

A chave é gerada e injetada somente neste processo, sem exibi-la ou gravá-la.
Ela continua obrigatória, em Base64 com pelo menos 32 bytes decodificados;
sem ela, o início falha com diagnóstico de `APP_JWT_SECRET_BASE64`. Reutilize a
mesma chave no reinício; gerar outra invalidará tokens quando auth existir.
Nunca use uma chave de teste/exemplo como chave de trabalho.

`backend/.env.example` é referência de nomes/defaults, **não é carregado pelo
Spring**. Exporte as variáveis no terminal ou injete pelo seu executor. Exemplo
de override antes de iniciar, usando outro diretório já existente:

```powershell
$env:APP_DB_PATH = 'C:\caminho\gravavel\foody-local.db'
```

`APP_JWT_ISSUER`, `APP_CORS_ALLOWED_ORIGINS`, `SERVER_ADDRESS` e `SERVER_PORT`
também permitem overrides. TTL (`APP_JWT_TTL_SECONDS`) e espera SQLite
(`APP_DB_BUSY_TIMEOUT_MS`) são positivos, com defaults 3600 s e 5000 ms.
Variáveis herdadas prevalecem sobre defaults: use um terminal dedicado e
confira overrides conhecidos ao trocar de contexto; não imprima secrets.

Encerre com **Ctrl+C** e aguarde `Graceful shutdown complete`. Para reiniciar
com os mesmos dados e chave, repita somente `java -jar` no mesmo terminal.
Os testes usam bancos temporários próprios e não acessam esse banco de dev.
Fechar o terminal descarta as variáveis da sessão, mas preserva o arquivo DB.
Não copie somente `.db` de banco ativo; preserve também o contexto de backup
consistente antes de qualquer manutenção dos seus dados.

## Frontend local — segundo terminal, a partir da raiz

```powershell
Set-Location .\frontend
npm ci
# Somente no primeiro setup; preserve seu arquivo se ele já existir.
if (-not (Test-Path .env.development.local)) {
    Copy-Item .env.development.example .env.development.local
}
npm run dev
```

Abra `http://127.0.0.1:5174`: esperado o título “Mini Rastreador de Pedidos”.
O script usa loopback e porta estrita: se ocupada, falha sem escolher outra.
Encerre com **Ctrl+C**. Reinicie após alterar variáveis/arquivo de ambiente.
Vite lê `.env.development.local`; o `.example` só funciona após cópia explícita.
Overrides do processo prevalecem sobre arquivos; nunca inclua secrets em
`VITE_*`, pois esses valores são públicos no bundle.

A base `/api` usa o proxy dev para `http://127.0.0.1:8081`, preservando o caminho.
O navegador acessa a mesma origem do frontend. Autenticação JWT e aplicação
HTTP das origens CORS ainda pertencem aos tickets próprios. Uma URL absoluta
em `VITE_API_BASE_URL` ignora o proxy e depende de CORS HTTP; use `/api` neste
setup. Em cloud/ambiente remoto, encaminhe as portas ao navegador pelo seu
executor e mantenha a base relativa; hostnames privados de containers não
são automaticamente alcançáveis pelo navegador. Nada é publicado por esses
comandos.

## Conferência — terceiro PowerShell

```powershell
curl.exe -i http://127.0.0.1:8081/api/orders
curl.exe -i http://127.0.0.1:5174/api/orders
```

Ambas devem devolver **HTTP 401**. Isso prova alcance à segurança, sem provar
operações de pedidos. HTTP 200 com HTML nessa URL não é sucesso de API.
Backend parado: chamada direta falha na conexão e proxy retorna HTTP 502;
confira `ECONNREFUSED` no Vite. Reinicie o mesmo JAR/banco no primeiro terminal.

Para porta ocupada, identifique o processo sem encerrá-lo:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8081,5174 -ErrorAction SilentlyContinue |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

Se você mudar a porta backend, ajuste o destino em `frontend/vite.config.js`.
Se mudar a porta frontend com `npm run dev -- --port 5175`, ajuste sua URL e a
origem `APP_CORS_ALLOWED_ORIGINS` correspondente. Não encerre processos alheios.
Se `npm ci` informar EPERM em módulo nativo, encerre seu Vite pelo terminal de
origem e tente novamente; o arquivo pode estar aberto em outro processo.

## Linux/macOS — mesmo setup, sem depender de Windows

No primeiro shell, a partir da raiz:

```sh
cd backend
sh ./mvnw verify
mkdir -p data
export SPRING_PROFILES_ACTIVE=local
export APP_JWT_SECRET_BASE64="$(node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64'))")"
java -jar target/delivery-0.0.1-SNAPSHOT.jar
```

No segundo shell, a partir da raiz:

```sh
cd frontend
npm ci
if [ ! -e .env.development.local ]; then cp .env.development.example .env.development.local; fi
npm run dev
```

URLs/resultados iguais aos anteriores; `curl -i` substitui `curl.exe -i`.
Encerre cada servidor com **Ctrl+C**. Reinicie o backend no mesmo shell para
reutilizar chave e banco. Os comandos Linux/macOS são equivalentes de setup;
a verificação executável desta versão foi realizada no Windows.

## Testes e builds

No backend: `./mvnw.cmd verify` (PowerShell) ou `sh ./mvnw verify` (POSIX).
Os testes não precisam de chave/banco de trabalho nem do arquivo frontend.
No frontend, após `npm ci` e a cópia do exemplo development:

```powershell
node --test src/config/env.test.js
npm run build:dev
npm run preview -- --host 127.0.0.1 --port 4174 --strictPort
```

Esperado: testes aprovados, `dist` gerado e tela em `http://127.0.0.1:4174`.
Encerre preview com **Ctrl+C**. Preview não fornece o proxy dev `/api` e não é
servidor de produção; a base relativa exige um reverse proxy no alvo de deploy.

Exemplos de builds hml/prod com URL **fictícia**, somente para conferir compilação:

```powershell
$env:VITE_APP_ENV = 'hml'
$env:VITE_API_BASE_URL = 'https://api-hml.example.invalid/api'
npm run build:hml
$env:VITE_APP_ENV = 'prod'
$env:VITE_API_BASE_URL = 'https://api.example.invalid/api'
npm run build:prod
Remove-Item Env:VITE_APP_ENV, Env:VITE_API_BASE_URL
```

`npm run build` equivale ao build production. Cada build substitui `dist`;
Vite embute a configuração na compilação. Development/local, hml/hml e
production/prod são combinações obrigatórias. Ausência de URL ou divergência
é recusada; hml/prod exigem URL HTTPS com base `/api`, sem credenciais/query e
sem loopback. `.env.development.local` não configura outros modos.

Spring exige exatamente um perfil local/hml/prod; `test` é exclusivo do harness
e não acompanha o JAR. Hml/prod continuam exigindo caminho SQLite absoluto,
chave, issuer e origens explícitas, sem herdar defaults locais. Configuração e
builds não comprovam deploy nem jornadas de cadastro/login/pedidos.

## Homologação — configuração sem implantação

Use o mesmo JAR verificado da revisão; selecione `SPRING_PROFILES_ACTIVE=hml`
no processo. Antes de iniciar, injete `APP_DB_PATH` absoluto para um arquivo em
diretório externo existente/gravável, `APP_JWT_ISSUER` exclusivo de hml,
`APP_JWT_SECRET_BASE64` próprio de hml (mínimo 32 bytes decodificados) e
`APP_CORS_ALLOWED_ORIGINS` com origens HTTPS exatas, separadas por vírgula,
sem caminhos/wildcards/loopback. Não reutilize banco, chave ou issuer local/prod.
Não há fallback para esses valores; o início incompleto falha antes de criar DB.
As variáveis são injetadas pelo executor; não grave chaves em arquivos versionados.
O armazenamento deve ser persistente, com um backend escritor; sua durabilidade
precisa ser verificada no futuro alvo autorizado.

No frontend, em um PowerShell dedicado dentro de `frontend`:

```powershell
npm ci
if (-not (Test-Path .env.hml.local)) { Copy-Item .env.hml.example .env.hml.local }
# Preencha VITE_API_BASE_URL em .env.hml.local com a URL HTTPS real, incluindo /api.
# O placeholder vazio falha de propósito; .example não é carregado pelo Vite.
npm run build:hml
```

Esperado após preencher: `dist` com a URL pública de hml. O script já usa
`vite build --mode hml`; NODE_ENV deve ser production (default do build),
nunca development para simular hml. `dist` é específico do modo/URL e precisa
ser recompilado se estes mudarem. Não injete segredos em VITE_*.
O comando termina sozinho; eventual preview local encerra com Ctrl+C e não
fornece proxy da API nem representa servidor hml.

O perfil hml mantém INFO geral, desabilita logs JDBC/SQL e access log,
limita web/security a WARN, sem detalhes de request/debug/trace. Erros ao
cliente omitem mensagem interna, binding, exception e stacktrace. Não habilite
logs de Authorization/corpos com endereço ou overrides de debug no alvo.
Auth e CORS HTTP serão implementados nos tickets próprios. Provedor, domínios,
certificados, credenciais reais e deploy continuam pendentes de definição;
nenhuma URL fictícia usada no teste é um destino de homologação implantado.

## Produção — configuração e artefatos, sem implantação

Use o mesmo JAR verificado, com `SPRING_PROFILES_ACTIVE=prod` e configuração
externa ao artefato. Exija `APP_DB_PATH` absoluto em um volume durável externo
ao checkout/artefato, com diretório pai já existente e gravável pelo usuário do
processo. O arquivo, se existir, deve ser regular e gravável. O início recusa
caminho relativo, banco em memória, diretório/arquivo inválido ou insumos
ausentes; não cria diretórios nem escolhe um volume por você. A checagem local
de acesso não comprova durabilidade do volume nem impede alterações de ACL
posteriores: confirme essas condições no alvo antes de operar.

Injete também `APP_JWT_SECRET_BASE64` exclusivo de prod (mínimo 32 bytes
decodificados), `APP_JWT_ISSUER` próprio e `APP_CORS_ALLOWED_ORIGINS` com
origens HTTPS exatas do frontend. Não reutilize banco, chave ou issuer de
local/hml; não existe fallback para esses valores nem seed de conta/senha.
Não grave secrets no Git, no bundle ou em argumentos de linha de comando.
Use somente um backend escritor sobre armazenamento local durável; disco
efêmero, arquivo em rede e múltiplas réplicas escritoras não são alvos desta
configuração. Não há alta disponibilidade ou hospedagem provisionada.

No frontend, em um PowerShell dedicado dentro de `frontend`:

```powershell
npm ci
if (-not (Test-Path .env.production.local)) {
    Copy-Item .env.production.example .env.production.local
}
# Preencha VITE_API_BASE_URL com a URL HTTPS pública real da API, incluindo /api.
npm run build:prod
```

Esperado após preencher: `dist` específico de prod. Sem URL, com loopback/HTTP
ou com `NODE_ENV` diferente de production o build falha. `npm run build`
também usa production. A configuração pública fica embutida no build; mudar
variáveis no servidor depois não altera esse `dist`. Recompile ao trocar a
URL e publique os arquivos estáticos com o servidor apropriado ao alvo.
Vite dev/preview servem apenas à verificação local. Os builds terminam sozinhos;
eventual preview encerra com Ctrl+C. Backend em primeiro plano também encerra
com Ctrl+C, aguardando o shutdown gracioso.

Prod mantém INFO geral, JDBC/SQL e access log desabilitados, web/security em
WARN, sem debug/trace/detalhes de request. O log bruto de exceções de request
do container fica desabilitado para evitar imprimir SQL/corpos/segredos;
diagnósticos de negócio sanitizados serão tratados junto aos endpoints.
Respostas de erro omitem mensagem interna, binding, exception e stacktrace.
Não habilite overrides que imprimam Authorization, senha/hash ou endereço.
Não há Actuator/endpoint público de diagnóstico.

O alvo deverá fornecer HTTPS, certificados, cabeçalhos de segurança e acesso
restrito à porta do backend por reverse proxy. `server.forward-headers-strategy`
é `NONE`: o backend ignora Forwarded/X-Forwarded-* até que os proxies confiáveis
e a topologia sejam definidos e verificados. Não habilite confiança ampla em
cabeçalhos enviados pela internet. Esta etapa não configura provedor, TLS,
DNS, proxy real nem CORS/autenticação funcionais.

Antes de manutenção/backup, pare o único backend escritor e confirme o fim
do processo/conexões. Faça uma cópia consistente do conjunto SQLite do volume
com o banco fechado; não copie apenas `.db` ativo nem descarte sidecars. Guarde
backup protegido e confira recuperação em um destino isolado antes de depender
dele. Backup online, se necessário, exige ferramenta SQLite apropriada e
procedimento próprio. Nenhum backup/restore de dados reais é feito pelo build.

Para rollback, pare a aplicação, preserve o volume e selecione o JAR, `dist`
e configuração externa anteriores compatíveis entre si e com o schema atual.
Reinicie com o mesmo caminho de dados; não restaure DB nem apague dados por
erro de configuração. Se houver mudança incompatível de schema, interrompa o
rollback e prepare um plano específico. Testes de perfil usam arquivos
temporários e não validam backup, TLS ou durabilidade de uma produção real.

## Verificar isolamento de ambientes

No PowerShell, com JDK 21/Node/npm disponíveis:

```powershell
# Na pasta backend:
.\mvnw.cmd '-Dtest=EnvironmentIsolationTest' test
.\mvnw.cmd verify

# Na pasta frontend:
npm run verify:env
```

Esperado: testes backend aprovados e `result: PASS` no frontend. A matriz
SQLite usa somente arquivos temporários próprios de local/hml/prod/test,
verifica arquivos físicos distintos, rollback, reinício e FK/timeout por
conexão. Não lê nem grava o banco de desenvolvimento ou de um alvo remoto.

`verify:env` roda os testes de configuração e instala com `npm ci` em uma
cópia temporária limpa, sem copiar seus arquivos `.env`. Exige acesso ao npm
e usa somente URLs/chaves fictícias. Constrói development/hml/production
com sourcemaps, verifica ausência de marcadores secretos e rejeita entradas
inválidas. Uma sonda na cópia mantém o cliente Axios no bundle para conferir
endpoint/rótulo e diferença entre dist hml/prod; a tela inicial ainda não usa
esse cliente. A cópia é removida ao terminar, inclusive em falha, preservando
fontes, `.env`, `node_modules` e `dist` do projeto. Os comandos terminam
sozinhos e não deixam servidores ativos.

Builds hml/prod com URLs diferentes precisam ser compilados separadamente;
trocar variáveis no runtime não reconfigura assets já gerados. Essa prova é
de configuração e fixtures. JWT de outro emissor, sessão persistente, CORS
efetivo e erros dos endpoints de negócio serão verificados após a
implementação da autenticação/API. Não comprova deploy ou isolamento físico
de infraestrutura remota.
