param(
    [Parameter(Position = 0)]
    [string]$Mode = "safe"
)

$ErrorActionPreference = "Stop"

function Get-FreeDriveLetter {
    $preferredLetters = @("Z:", "Y:", "X:", "W:", "V:", "U:", "T:", "S:", "R:", "Q:", "P:")
    foreach ($letter in $preferredLetters) {
        if (-not (Test-Path -LiteralPath ($letter + "\"))) {
            return $letter
        }
    }
    throw "No free drive letter is available for temporary path shortening."
}

function Remove-IfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathToRemove
    )

    if (Test-Path -LiteralPath $PathToRemove) {
        Write-Host "Removing $PathToRemove"
        for ($attempt = 1; $attempt -le 3 -and (Test-Path -LiteralPath $PathToRemove); $attempt++) {
            try {
                Remove-Item -LiteralPath $PathToRemove -Recurse -Force -ErrorAction SilentlyContinue
            } catch {
            }
            if (Test-Path -LiteralPath $PathToRemove) {
                Start-Sleep -Milliseconds 200
            }
        }

        if (Test-Path -LiteralPath $PathToRemove) {
            $item = Get-Item -LiteralPath $PathToRemove -Force -ErrorAction SilentlyContinue
            if ($item -and $item.PSIsContainer) {
                [System.IO.Directory]::Delete($PathToRemove, $true)
            } elseif ($item) {
                [System.IO.File]::Delete($PathToRemove)
            }
        }

        if (Test-Path -LiteralPath $PathToRemove) {
            throw "Failed to remove $PathToRemove"
        }
    }
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectName = Split-Path -Leaf $projectRoot
$hardMode = $Mode -ieq "hard"
$mappedDrive = $null

$ideaProcesses = Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -match "^(idea64|idea|fsnotifier64|fsnotifier)$"
}

if ($ideaProcesses) {
    throw "Please close IntelliJ IDEA before running this script."
}

Write-Host "Project root: $projectRoot"
Write-Host ("Cleanup mode: " + ($(if ($hardMode) { "hard" } else { "safe" })))

try {
    <#
        这里先把项目根目录临时映射成一个短盘符。

        原因是这些 Mod 工程目录名很长，再叠加 .gradle/loom-cache 的嵌套层级，
        Windows 很容易在删除时撞到长路径限制，表现成“文件明明在删，但中途某条路径报找不到”。

        用 Z:/Y:/X: 这类短盘符重新映射后，删除路径会短很多，稳定性也会高很多。
    #>
    $mappedDrive = Get-FreeDriveLetter
    & subst $mappedDrive $projectRoot | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create temporary drive mapping for $projectRoot"
    }
    $projectWorkRoot = $mappedDrive + "\"

    $projectScopedTargets = @(
        (Join-Path $projectWorkRoot ".gradle"),
        (Join-Path $projectWorkRoot "build"),
        (Join-Path $projectWorkRoot "out"),
        (Join-Path $projectWorkRoot ".idea\modules"),
        (Join-Path $projectWorkRoot ".idea\libraries"),
        (Join-Path $projectWorkRoot ".idea\workspace.xml"),
        (Join-Path $projectWorkRoot ".idea\modules.xml")
    )

    foreach ($target in $projectScopedTargets) {
        Remove-IfExists -PathToRemove $target
    }

    Get-ChildItem -LiteralPath $projectWorkRoot -Force -File -Filter "*.iml" -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-IfExists -PathToRemove $_.FullName
    }

    $ideaRoot = Join-Path $env:LOCALAPPDATA "JetBrains\IntelliJIdea2026.1"
    $projectCacheRoot = Join-Path $ideaRoot "projects"

    if (Test-Path -LiteralPath $projectCacheRoot) {
        Get-ChildItem -LiteralPath $projectCacheRoot -Directory -Force | Where-Object {
            $_.Name.StartsWith($projectName, [System.StringComparison]::OrdinalIgnoreCase)
        } | ForEach-Object {
            Remove-IfExists -PathToRemove $_.FullName
        }
    }

    if ($hardMode) {
        Write-Host "Hard mode enabled. Removing shared IntelliJ 2026.1 indexes."
        $globalTargets = @(
            (Join-Path $ideaRoot "caches"),
            (Join-Path $ideaRoot "index"),
            (Join-Path $ideaRoot "compile-server"),
            (Join-Path $ideaRoot "compiler"),
            (Join-Path $ideaRoot "global-model-cache"),
            (Join-Path $ideaRoot "tmp")
        )

        foreach ($target in $globalTargets) {
            Remove-IfExists -PathToRemove $target
        }
    }

    Write-Host "IDEA cache cleanup completed successfully."
} finally {
    if ($mappedDrive) {
        & subst $mappedDrive /d | Out-Null
    }
}
