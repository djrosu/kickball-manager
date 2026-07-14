# Kickball Manager

A Spring Boot starter application for managing a weekly singles kickball league.

## What is included

- Java 21 / Spring Boot 3.5.x
- Thymeleaf server-rendered pages
- Spring Security form login
- Spring Data JPA domain model
- H2 local development database
- PostgreSQL dependency and Docker Compose setup
- Starter player home page
- Starter manager dashboard
- Basic roster generation
- Basic run scoring and leaderboard


## Development login

The seed data creates a few test users. Use any of these emails with password `password`:

- `dan@example.com` — master manager
- `cara@example.com` — manager/player
- `alice@example.com` — player
- `ben@example.com` — player

## Run locally from IntelliJ

1. Open this folder as a Maven project.
2. Make sure the project SDK is Java 21.
3. Run `KickballManagerApplication`.
4. Open `http://localhost:8080`.

## Run from command line

```bash
mvn spring-boot:run
```

## H2 console

Local H2 console is enabled at:

```text
http://localhost:8080/h2-console
```

Use:

```text
JDBC URL: jdbc:h2:mem:kickball;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
User: sa
Password: <blank>
```

## Docker Compose

```bash
docker compose up --build
```

This starts PostgreSQL and the app on port 8080.

## Important next steps

This is intentionally a starter framework. The most important next development tasks are:

1. Replace `DataInitializer` with a proper manager-only player admin/import workflow.
2. Add password reset tokens and email sending.
3. Improve roster generation to consider mutual teammate preferences.
4. Add manual roster add/remove/move controls.
5. Add batting order drag/drop or up/down controls.
6. Add game state controls for current batter and switching teams at bat.
7. Add MP3 upload/linking for walk-up songs.
8. Add real database migrations using Flyway or Liquibase before production.

## Production note

The `application-prod.yml` profile uses `ddl-auto: validate`. Before deploying to AWS, add Flyway or Liquibase migration scripts so the database schema is created and evolved safely.
