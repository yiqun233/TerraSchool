#Requires -Version 7.0
<#
本地停止入口：通知由 start-local.ps1 启动的前后端进程退出。

只停止本项目的前后端，不停止共享的 WSL PostgreSQL，也不停止 WSL 本身；
演示账号与数据保留。启动窗口里按 Ctrl+C 的效果与此相同。
#>

$ErrorActionPreference = 'Stop'

# 与 python -X utf8 的输出编码对齐，避免中文在控制台乱码。
$utf8 = [System.Text.UTF8Encoding]::new($false)
if ([Console]::OutputEncoding.CodePage -ne 65001) {
    chcp 65001 | Out-Null
    [Console]::OutputEncoding = $utf8
}
$OutputEncoding = $utf8

Set-Location -LiteralPath $PSScriptRoot

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host '未找到 python，请先把 Python 3 加入 PATH 后重试。' -ForegroundColor Red
    exit 1
}

& python -X utf8 tools\dev\run.py stop
if ($LASTEXITCODE -ne 0) {
    Write-Host '停止失败，请按上面的提示修复后重试。' -ForegroundColor Red
    exit 1
}
exit 0
