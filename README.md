# Mini Rastreador de Pedidos — Foody Delivery

Fundação em Java 21/Spring Boot/SQLite e React/JavaScript/Vite. Neste checkpoint,
a interface apresenta somente a tela inicial; cadastro, login e pedidos ainda
serão implementados. A API permanece fechada por segurança.

## Pré-requisitos

- JDK 21, com `JAVA_HOME` apontando para a instalação e `bin` no `PATH`.
- Node.js >= 22.22.0 e npm. Verificado com Node 24.18.0/npm 11.16.0.
- Acesso aos repositórios Maven/npm para o primeiro download das dependências.

O Maven Wrapper baixa a versão 3.9.11; não é necessário Maven global. Confira
`java -version`, `javac -version`, `node --version` e `npm --version`.

## Backend — PowerShell, a partir da raiz do repositório

```powershell
Set-Location .\backend
.\mvnw.cmd verify

# Cria um banco novo e isolado. Guarde $database para reutilizar no reinício.
$databaseDirectory = Join-Path ([IO.Path]::GetTempPath()) ('foody-foundation-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $databaseDirectory | Out-Null
$database = Join-Path $databaseDirectory 'foundation.db'
java -jar .\target\delivery-0.0.1-SNAPSHOT.jar "--spring.datasource.url=jdbc:sqlite:$database" --server.address=127.0.0.1 --server.port=8081
```

Esperado: `BUILD SUCCESS`, 15 testes aprovados e backend em
`http://127.0.0.1:8081`. O schema é criado no arquivo escolhido, preservando os
registros existentes. O diretório pai precisa existir e ser gravável.
Os testes automatizados usam outros arquivos temporários próprios.

Encerre com **Ctrl+C** e aguarde `Graceful shutdown complete`. Para reiniciar
com o mesmo banco, repita somente o comando `java -jar` no mesmo terminal.
Em outro terminal, atribua a `$database` o caminho absoluto que foi escolhido;
não execute novamente a criação de diretório se quiser reutilizar os dados.

Se o JDK já estiver instalado mas não aparecer no terminal, configure apenas
a sessão atual (ajuste o caminho para sua instalação):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Frontend — segundo PowerShell, a partir da raiz do repositório

```powershell
Set-Location .\frontend
npm ci
$env:VITE_API_BASE_URL = '/api'
npm run dev -- --host 127.0.0.1 --port 5174 --strictPort
```

Abra `http://127.0.0.1:5174`: esperado o título “Mini Rastreador de Pedidos”.
Encerre com **Ctrl+C**. Fechar o terminal descarta as variáveis desta sessão.

O proxy de desenvolvimento encaminha `/api` para `http://127.0.0.1:8081`,
preservando o caminho. O navegador usa a mesma origem do frontend; não é
necessário liberar CORS para esta execução. Uma URL absoluta em
`VITE_API_BASE_URL` ignora esse proxy e exige CORS no backend, ainda pendente da
etapa de configuração dos ambientes. Nenhum segredo deve ir em `VITE_*`.

## Verificação rápida — terceiro PowerShell

```powershell
curl.exe -i http://127.0.0.1:8081/api/orders
curl.exe -i http://127.0.0.1:5174/api/orders
```

Ambas devem devolver **HTTP 401**, sem sessão. Isso comprova que a requisição
alcançou a segurança do backend; não comprova uma operação de pedidos já
implementada. Um HTTP 200 com HTML nessa URL não é sucesso de API.

Com o backend encerrado, a primeira chamada falha na conexão e a segunda
retorna erro do proxy (HTTP 502), distinto do 401. Confira o terminal do Vite
para `ECONNREFUSED`. Depois reinicie o backend com o mesmo `$database`.

Se uma porta estiver ocupada, identifique seu processo sem encerrá-lo:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8081,5174 -ErrorAction SilentlyContinue |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

Use outra porta livre para o Vite e ajuste sua URL de acesso. Se trocar a porta
do backend, ajuste também o destino do proxy em `frontend/vite.config.js`.

## Build do frontend

Depois de encerrar o Vite, no diretório `frontend`:

```powershell
npm run build
npm run preview -- --host 127.0.0.1 --port 4174 --strictPort
```

Esperado: `dist` gerado e a tela inicial em `http://127.0.0.1:4174`.
Encerre com **Ctrl+C**. Preview serve para conferir o build local; configuração
local/homologação/produção e publicação serão tratadas em etapas posteriores.
