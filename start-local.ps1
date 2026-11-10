#Requires -Version 7.0
<#
本地启动入口：检查 WSL PostgreSQL、必要时迁移与构建，然后在前台运行前后端。

后端 Spring 与前端 Vite 的日志实时打印在本窗口，带 [backend] / [frontend] 前缀区分来源；
同一份内容同时写入 .local\dev\backend.log、frontend.log。首次构建的 Maven/pnpm 输出也会上屏，
不会再出现长时间没有输出、看不出是否卡住的情况。

停止：在本窗口按 Ctrl+C，或在另一个窗口运行 .\stop-local.ps1。
共享的 WSL PostgreSQL 不会被停止，演示数据保留。
#>
[CmdletBinding()]
param(
    # 启动成功后不自动打开浏览器（透传给 tools\dev\run.py）。
    [switch]$NoOpen
)

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

Write-Host 'TeraSchool 本地环境' -ForegroundColor Cyan
Write-Host '首次运行会迁移数据库并构建，可能几分钟；之后只启动前后端。' -ForegroundColor DarkGray
Write-Host '日志实时显示在本窗口，同一份写入 .local\dev\*.log。' -ForegroundColor DarkGray
Write-Host '停止：按 Ctrl+C，或在另一个窗口运行 .\stop-local.ps1。' -ForegroundColor DarkGray
Write-Host ''

$arguments = @('-X', 'utf8', 'tools\dev\run.py', 'start')
if ($NoOpen) { $arguments += '--no-open' }

& python @arguments
$code = $LASTEXITCODE

switch ($code) {
    0 {
        Write-Host '服务已结束；WSL PostgreSQL 与数据保留。' -ForegroundColor Yellow
        exit 0
    }
    3 {
        Write-Host '前后端已经在运行，不重复启动；页面地址见上面的输出。' -ForegroundColor Yellow
        Write-Host '本窗口可以直接关闭，不会影响已运行的服务。' -ForegroundColor DarkGray
        exit 0
    }
    { $_ -eq 130 -or $_ -eq -1073741510 -or $_ -eq 3221225786 } {
        # Ctrl+C 由控制台直接结束进程，不是启动失败。
        Write-Host '已按 Ctrl+C 停止前后端；WSL PostgreSQL 与数据保留。' -ForegroundColor Yellow
        exit 0
    }
    default {
        Write-Host '启动失败，请按上面的提示修复后重试。' -ForegroundColor Red
        Write-Host '完整日志：.local\dev\backend.log、backend-build.log、frontend.log' -ForegroundColor DarkGray
        exit 1
    }
}
