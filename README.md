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
| Testes | JUnit, Spring Boot Test, Node Test Runner, Vitest e Testing Library |

## Executando localmente

Pré-requisitos: **JDK 21**, **Node.js 20.19 ou 22.12+** e npm.

### Backend

O perfil `local` cria a pasta do SQLite e gera uma chave JWT efêmera automaticamente.

Windows PowerShell:

```powershell
Set-Location .\backend
.\mvnw.cmd "-Dspring-boot.run.profiles=local" spring-boot:run
```

Linux ou macOS:

```bash
cd backend
./mvnw -Dspring-boot.run.profiles=local spring-boot:run
```

A API ficará em `http://127.0.0.1:8081`, o SQLite em `backend/data/foody-local.db` e o Swagger em `http://127.0.0.1:8081/swagger-ui.html`. Como a chave local é efêmera, reiniciar o backend encerra as sessões existentes.

### Frontend

Windows PowerShell:

```powershell
Set-Location .\frontend
npm ci
npm run dev
```

Linux ou macOS:

```bash
cd frontend
npm ci
npm run dev
```

Acesse `http://127.0.0.1:5174`. O modo local usa `/api` por padrão e não exige arquivo `.env`.

## Principais endpoints

| Método | Endpoint | Descrição |
| --- | --- | --- |
| `POST` | `/api/auth/register` | Cadastra um usuário |
| `POST` | `/api/auth/login` | Autentica e retorna o JWT |
| `POST` | `/api/orders` | Cria um pedido |
| `GET` | `/api/orders` | Lista os pedidos da conta autenticada |
| `GET` | `/api/orders/{id}` | Consulta um pedido por ID |
| `PATCH` | `/api/orders/{id}/status` | Atualiza o status do pedido |

O `PATCH` usa a versão retornada na última leitura para impedir sobrescritas concorrentes:

```bash
curl -X PATCH http://127.0.0.1:8081/api/orders/1/status \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"status":"EM_PREPARO","expectedVersion":0}'
```

Exemplos de autenticação:

```bash
curl -X POST http://127.0.0.1:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Usuário Teste","email":"teste@example.com","password":"senha-segura"}'

curl -X POST http://127.0.0.1:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"teste@example.com","password":"senha-segura"}'
```

## Testes

Com os servidores encerrados:

```powershell
# Backend
Set-Location .\backend
.\mvnw.cmd verify

# Frontend
Set-Location ..\frontend
npm test
npm run test:components
npm run build:dev
```

A validação cobre backend, serviços frontend, componentes React e jornadas integradas de autenticação, pedidos, concorrência, persistência, rede e ViaCEP. O workflow em `.github/workflows/ci.yml` executa as suítes e o build em pushes e pull requests.

## Estrutura

```text
backend/   API REST, autenticação, regras de pedidos e SQLite
frontend/  interface React, sessão, formulários e integração com a API
```

O repositório mantém o histórico de implementação em commits organizados por funcionalidade.
