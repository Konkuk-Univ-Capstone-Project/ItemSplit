# ItemSplit

졸업 프로젝트 백엔드입니다.

## 로컬 실행

### 1. 환경 변수 준비

기본값으로도 실행할 수 있지만, 팀 공통 설정을 맞추려면 예시 파일을 복사해서 `.env`를 만듭니다.

macOS

```bash
cp .env.example .env
```

Windows PowerShell

```powershell
Copy-Item .env.example .env
```

### 2. DB 실행

```bash
docker compose up -d
```

DB 상태 확인

```bash
docker compose ps
```

### 3. 서버 실행

macOS

```bash
./gradlew bootRun
```

Windows

```powershell
gradlew.bat bootRun
```

기본 프로파일은 `local`이며, 로컬 DB 접속 정보는 `.env` 또는 기본값을 사용합니다.

### 4. 헬스 체크

macOS

```bash
curl http://localhost:8080/actuator/health
```

Windows PowerShell

```powershell
curl.exe http://localhost:8080/actuator/health
```

정상 실행 시 `{"status":"UP"}` 응답을 확인할 수 있습니다.

## 운영 프로파일

운영 환경에서는 `prod` 프로파일을 사용하며 아래 환경 변수를 반드시 주입해야 합니다.

- `SPRING_PROFILES_ACTIVE=prod`
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_SECRET`

운영 프로파일은 `ddl-auto=validate`로 동작합니다.
