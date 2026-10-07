[CmdletBinding()]
param(
    [string]$ContextPath = (Join-Path $PSScriptRoot '../../../context')
)

$ErrorActionPreference = 'Stop'

$corePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolvedContextPath = (Resolve-Path -LiteralPath $ContextPath).Path

$snapshots = @(
    [pscustomobject]@{ Source = 'AI_AGENT.md'; Snapshot = 'docs/AI_AGENT.md' }
)

$contextDocsPath = Join-Path $resolvedContextPath 'docs'
$contextDocs = Get-ChildItem -LiteralPath $contextDocsPath -Filter '*.md' -File | Sort-Object Name
foreach ($contextDoc in $contextDocs) {
    $relativePath = 'docs/{0}' -f $contextDoc.Name
    $snapshots += [pscustomobject]@{ Source = $relativePath; Snapshot = $relativePath }
}

function Get-NormalizedContent {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.File]::ReadAllText($Path).Replace("`r`n", "`n")
}

$failures = @()
foreach ($mapping in $snapshots) {
    $sourcePath = Join-Path $resolvedContextPath $mapping.Source
    $snapshotPath = Join-Path $corePath $mapping.Snapshot

    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        $failures += "missing context source: $($mapping.Source)"
        continue
    }

    if (-not (Test-Path -LiteralPath $snapshotPath -PathType Leaf)) {
        $failures += "missing Core snapshot: $($mapping.Snapshot)"
        continue
    }

    $sourceContent = Get-NormalizedContent -Path $sourcePath
    $snapshotContent = Get-NormalizedContent -Path $snapshotPath
    if ($sourceContent -cne $snapshotContent) {
        $failures += "content mismatch: $($mapping.Source) -> $($mapping.Snapshot)"
    }
}

$expectedSnapshots = @($snapshots | ForEach-Object { $_.Snapshot })
$coreLocalDocs = @('docs/README.md', 'docs/GIT_WORKFLOW.md')
$coreDocsPath = Join-Path $corePath 'docs'
$coreDocs = Get-ChildItem -LiteralPath $coreDocsPath -Filter '*.md' -File
foreach ($coreDoc in $coreDocs) {
    $relativePath = 'docs/{0}' -f $coreDoc.Name
    if (($relativePath -notin $expectedSnapshots) -and ($relativePath -notin $coreLocalDocs)) {
        $failures += "unexpected Core snapshot without context source: $relativePath"
    }
}

if ($failures.Count -gt 0) {
    Write-Error ("Context document snapshot verification failed:`n- " + ($failures -join "`n- "))
}

Write-Host "Context document snapshots are synchronized ($($snapshots.Count) files)."
