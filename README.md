# Saffron Platform Backend

Spring Boot REST API for Saffron Cash Flow (restaurant daily reports, payroll, analytics).

## Stack

- Java 21 · Spring Boot 3.4 · PostgreSQL · JWT auth

## Local run

```bash
# Postgres (Docker)
docker compose up -d

# API
mvn spring-boot:run
```

API: http://localhost:3001/api/health

## Configuration

Copy `.env.example` to `.env` or set environment variables:

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | JDBC URL (default local Postgres) |
| `SPRING_DATASOURCE_USERNAME` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `APP_JWT_SECRET` | Min 32 characters |

## Deploy

Push to `main` → GitHub Actions builds Docker image and deploys to VPS.

See [deploy/README.md](deploy/README.md).

## Demo users (seeded on first run)

| Email | Password | Role |
|-------|----------|------|
| admin@saffron.local | admin123 | Admin |
| cashier@saffron.local | cashier123 | Cashier |
