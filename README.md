# Mini Rastreador de Pedidos — Foody Delivery

Aplicação full-stack desenvolvida para o desafio técnico da **Foody Delivery**. O sistema permite criar uma conta, entrar com autenticação JWT e acompanhar pedidos de delivery do recebimento até a entrega ou o cancelamento.

## Requisitos atendidos

### Backend

- [x] Cadastro de usuário com nome, e-mail e senha
- [x] Login com e-mail e senha e autenticação JWT
- [x] Rotas de pedidos protegidas para usuários autenticados
- [x] Criação de pedidos com cliente, itens e endereço de entrega
- [x] Listagem de pedidos e consulta por ID
- [x] Atualização entre os status `RECEBIDO`, `EM_PREPARO`, `SAIU_PARA_ENTREGA`, `ENTREGUE` e `CANCELADO`
- [x] Persistência em SQLite

### Frontend

- [x] Aplicação React responsiva
- [x] Cadastro e login com sessão persistente
- [x] Criação e listagem dos pedidos da conta autenticada
- [x] Atualização de status com confirmação e tratamento de conflitos
- [x] Pesquisa por ID ou nome do cliente
- [x] Preenchimento de endereço por ViaCEP, mantendo edição manual disponível

## Tecnologias

| Camada | Tecnologias |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring Security, Spring JDBC, JWT e Maven |
| Frontend | React 19, React Router, Axios e Vite |
| Banco de dados | SQLite |
| Testes | JUnit, Spring Boot Test e Node Test Runner |

## Executando localmente

Pré-requisitos: **JDK 21**, **Node.js 22.22 ou superior** e npm.

### Backend

No PowerShell, a partir da raiz do projeto:

```powershell
Set-Location .\backend
New-Item -ItemType Directory -Force -Path .\data | Out-Null
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:APP_JWT_SECRET_BASE64 = [Convert]::ToBase64String(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
.\mvnw.cmd spring-boot:run
```

A API ficará disponível em `http://127.0.0.1:8081` e criará o banco local em `backend/data/foody-local.db`.

### Frontend

Em outro PowerShell, a partir da raiz:

```powershell
Set-Location .\frontend
npm ci
if (-not (Test-Path .env.development.local)) {
  Copy-Item .env.development.example .env.development.local
}
npm run dev
```

Acesse `http://127.0.0.1:5174`.

## Principais endpoints

| Método | Endpoint | Descrição |
| --- | --- | --- |
| `POST` | `/api/auth/register` | Cadastra um usuário |
| `POST` | `/api/auth/login` | Autentica e retorna o JWT |
| `POST` | `/api/orders` | Cria um pedido |
| `GET` | `/api/orders` | Lista os pedidos da conta autenticada |
| `GET` | `/api/orders/{id}` | Consulta um pedido por ID |
| `PATCH` | `/api/orders/{id}/status` | Atualiza o status do pedido |

## Testes

Com os servidores encerrados:

```powershell
# Backend
Set-Location .\backend
.\mvnw.cmd verify

# Frontend
Set-Location ..\frontend
npm test
npm run build:dev
```

A validação final do projeto aprovou **293 testes backend**, **115 testes frontend** e as jornadas integradas de autenticação, pedidos, concorrência, persistência, rede e ViaCEP.

## Estrutura

```text
backend/   API REST, autenticação, regras de pedidos e SQLite
frontend/  interface React, sessão, formulários e integração com a API
```

O repositório mantém o histórico de implementação em commits organizados por funcionalidade.
