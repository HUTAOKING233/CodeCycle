$ErrorActionPreference = 'Stop'

$services = @(
  @{ Name = 'gateway'; Path = 'backend\codecycle-gateway'; Port = 8080 },
  @{ Name = 'auth'; Path = 'backend\codecycle-auth'; Port = 8081 },
  @{ Name = 'project'; Path = 'backend\codecycle-project'; Port = 8082 },
  @{ Name = 'analysis'; Path = 'backend\codecycle-analysis'; Port = 8083 },
  @{ Name = 'knowledge'; Path = 'backend\codecycle-knowledge'; Port = 8084 },
  @{ Name = 'mcp'; Path = 'backend\codecycle-mcp'; Port = 8085 }
)

foreach ($service in $services) {
  $jar = Get-ChildItem "$($service.Path)\target\*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-sources|-javadoc' } |
    Select-Object -First 1

  if (-not $jar) {
    Write-Warning "未找到 $($service.Name) JAR，请先执行 mvn package。"
    continue
  }

  Start-Process java -ArgumentList @('-jar', $jar.FullName, '--spring.profiles.active=dev') -WorkingDirectory (Resolve-Path $service.Path)
  Write-Host "Started $($service.Name) on port $($service.Port)"
}
