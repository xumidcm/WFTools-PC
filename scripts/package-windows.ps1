#requires -Version 5.1
<#!
.SYNOPSIS
Packages an already-built Compose app image as a portable ZIP and a per-user MSI.
.DESCRIPTION
Does not invoke Gradle, install software, run the application, or run the MSI.
ValidateOnly is read-only. GenerateWixOnly writes an inspectable WiX definition,
but does not copy the app image or invoke candle/light. Normal packaging retains
its uniquely named staging directory and refuses to overwrite release artifacts.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AppImage,
    [ValidatePattern('^\d{1,3}\.\d{1,3}\.\d{1,5}$')][string]$Version = '0.1.6',
    [string]$OutputDirectory = '',
    [string]$VlcDirectory = 'D:\DevTools\WFTools-PC\vlc\vlc-3.0.23',
    [string]$VlcArchive = 'D:\DevTools\WFTools-PC\downloads\vlc-3.0.23-win64.zip',
    [ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$VlcArchiveSha256 = '992D19DBD0B8A7CDE9167D2F7780B1EF6F92ACC8A71ACFA736101A21F35181E1',
    [string]$WixDirectory = 'D:\DevTools\WFTools-PC\wix',
    [string]$VerifyMsi = '',
    [ValidatePattern('^[A-Za-z0-9_. -]+\.exe$')][string]$LauncherName = 'WFTools-PC.exe',
    [switch]$ValidateOnly,
    [switch]$PreparePortableOnly,
    [switch]$GenerateWixOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$packagingRoot = Join-Path $repositoryRoot 'packaging'
$artifactRoot = Join-Path $repositoryRoot 'artifacts'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = $artifactRoot }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$AppImage = [IO.Path]::GetFullPath($AppImage)
$VlcDirectory = [IO.Path]::GetFullPath($VlcDirectory)
$VlcArchive = [IO.Path]::GetFullPath($VlcArchive)
$WixDirectory = [IO.Path]::GetFullPath($WixDirectory)
$iconPath = Join-Path $packagingRoot 'WFTools-PC.ico'
$userGuidePath = Join-Path $repositoryRoot 'docs/windows/USER_GUIDE.md'
$upgradeCode = '19A1AC0C-FDD0-4411-9DD5-A2B95F1CE108'
$progId = 'ProgID.WFToolsPC.watch'
$wixNamespace = 'http://schemas.microsoft.com/wix/2006/wi'

function Assert-UnderDirectory([string]$Path, [string]$Root) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $boundary = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    if (-not ($absolute.Equals($boundary, [StringComparison]::OrdinalIgnoreCase) -or
        $absolute.StartsWith($boundary + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))) {
        throw "Path must stay inside ${boundary}: $absolute"
    }
}

function Get-RelativePath([string]$Root, [string]$Path) {
    Assert-UnderDirectory $Path $Root
    return [IO.Path]::GetFullPath($Path).Substring([IO.Path]::GetFullPath($Root).TrimEnd('\', '/').Length).TrimStart('\', '/')
}

function Get-StreamHash([IO.Stream]$Stream) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($Stream))).Replace('-', '') }
    finally { $algorithm.Dispose() }
}

function Get-StableId([string]$Prefix, [string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value.ToLowerInvariant())
    $stream = [IO.MemoryStream]::new($bytes, $false)
    try { return $Prefix + (Get-StreamHash $stream).Substring(0, 28) }
    finally { $stream.Dispose() }
}

function Get-ComponentGuid([string]$Value) {
    $stream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes(($upgradeCode + '/' + $Value).ToLowerInvariant()), $false)
    try { return [Guid]::ParseExact((Get-StreamHash $stream).Substring(0, 32), 'N').ToString('D').ToUpperInvariant() }
    finally { $stream.Dispose() }
}

function Assert-X64Pe([string]$Path) {
    if (-not [IO.File]::Exists($Path)) { throw "Missing x64 binary: $Path" }
    $reader = [IO.BinaryReader]::new([IO.File]::OpenRead($Path))
    try {
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "Not a Windows PE binary: $Path" }
        $reader.BaseStream.Position = 0x3C
        $header = $reader.ReadUInt32()
        if ($header + 6 -gt $reader.BaseStream.Length) { throw "Invalid PE header: $Path" }
        $reader.BaseStream.Position = $header
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) {
            throw "Expected Windows x64 PE machine 0x8664: $Path"
        }
    } finally { $reader.Dispose() }
}

function Copy-PreservedTree([string]$Source, [string]$Destination) {
    $sourceInfo = Get-Item -LiteralPath $Source -Force
    if ($sourceInfo.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse source is not packaged: $Source" }
    [void][IO.Directory]::CreateDirectory($Destination)
    $directories = @(Get-ChildItem -LiteralPath $Source -Directory -Recurse -Force)
    foreach ($directory in $directories) {
        if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse directory is not packaged: $($directory.FullName)" }
        [void][IO.Directory]::CreateDirectory((Join-Path $Destination (Get-RelativePath $Source $directory.FullName)))
    }
    foreach ($file in Get-ChildItem -LiteralPath $Source -File -Recurse -Force) {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse file is not packaged: $($file.FullName)" }
        $target = Join-Path $Destination (Get-RelativePath $Source $file.FullName)
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
        [IO.File]::Copy($file.FullName, $target, $true)
        [IO.File]::SetLastWriteTimeUtc($target, $file.LastWriteTimeUtc)
    }
    foreach ($directory in ($directories | Sort-Object { $_.FullName.Length } -Descending)) {
        [IO.Directory]::SetLastWriteTimeUtc((Join-Path $Destination (Get-RelativePath $Source $directory.FullName)), $directory.LastWriteTimeUtc)
    }
    [IO.Directory]::SetLastWriteTimeUtc($Destination, $sourceInfo.LastWriteTimeUtc)
}

function Assert-VlcSource {
    if (-not [IO.File]::Exists($VlcArchive)) { throw "Missing verified VLC source ZIP: $VlcArchive" }
    $archiveHash = (Get-FileHash -LiteralPath $VlcArchive -Algorithm SHA256).Hash
    if ($archiveHash -ne $VlcArchiveSha256) { throw "VLC ZIP SHA256 mismatch. Expected $VlcArchiveSha256, got $archiveHash" }
    $archive = [IO.Compression.ZipFile]::OpenRead($VlcArchive)
    $verified = [Collections.Generic.List[object]]::new()
    try {
        foreach ($name in @('libvlc.dll', 'libvlccore.dll', 'vlc-cache-gen.exe')) {
            $entry = @($archive.Entries | Where-Object { $_.FullName -match ('^[^/]+/' + [Regex]::Escape($name) + '$') })
            if ($entry.Count -ne 1) { throw "VLC ZIP does not contain one expected binary: $name" }
            $inputStream = $entry[0].Open()
            try { $expected = Get-StreamHash $inputStream } finally { $inputStream.Dispose() }
            $source = Join-Path $VlcDirectory $name
            Assert-X64Pe $source
            $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
            if ($actual -ne $expected) { throw "Extracted VLC binary differs from verified ZIP: $source" }
            $verified.Add([pscustomobject]@{ File = $name; Sha256 = $actual })
        }
    } finally { $archive.Dispose() }
    return $verified.ToArray()
}

function Add-WixNode([Xml.XmlNode]$Parent, [string]$Name, [Collections.IDictionary]$Attributes) {
    $node = $script:packageWixDocument.CreateElement($Name, $wixNamespace)
    if ($null -ne $Attributes) {
        foreach ($key in $Attributes.Keys) { $node.SetAttribute([string]$key, [string]$Attributes[$key]) }
    }
    [void]$Parent.AppendChild($node)
    return $node
}

function New-WixDefinition([string]$Payload, [string]$Destination, [string]$ExtraVlcSource = '') {
    $script:packageWixDocument = [Xml.XmlDocument]::new()
    [void]$script:packageWixDocument.AppendChild($script:packageWixDocument.CreateXmlDeclaration('1.0', 'UTF-8', $null))
    $wix = Add-WixNode $script:packageWixDocument 'Wix' @{}
    $product = Add-WixNode $wix 'Product' @{
        Id = '*'; Name = 'WFTools-PC'; Language = '1033'; Version = $Version
        Manufacturer = 'WFTools-PC'; UpgradeCode = $upgradeCode; Codepage = '1252'
    }
    [void](Add-WixNode $product 'Package' @{ InstallerVersion = '500'; Compressed = 'yes'; InstallScope = 'perUser'; InstallPrivileges = 'limited'; Platform = 'x64' })
    [void](Add-WixNode $product 'MajorUpgrade' @{ DowngradeErrorMessage = 'A newer version of WFTools-PC is already installed.'; Schedule = 'afterInstallInitialize'; AllowSameVersionUpgrades = 'yes' })
    [void](Add-WixNode $product 'MediaTemplate' @{ EmbedCab = 'yes'; CompressionLevel = 'high' })
    $condition = Add-WixNode $product 'Condition' @{ Message = 'WFTools-PC requires 64-bit Windows.' }
    $condition.InnerText = 'VersionNT64'
    [void](Add-WixNode $product 'Icon' @{ Id = 'ApplicationIcon'; SourceFile = $iconPath })
    [void](Add-WixNode $product 'Property' @{ Id = 'ARPPRODUCTICON'; Value = 'ApplicationIcon' })
    [void](Add-WixNode $product 'Property' @{ Id = 'ARPNOMODIFY'; Value = '1' })
    $target = Add-WixNode $product 'Directory' @{ Id = 'TARGETDIR'; Name = 'SourceDir' }
    $local = Add-WixNode $target 'Directory' @{ Id = 'LocalAppDataFolder' }
    $programs = Add-WixNode $local 'Directory' @{ Id = 'UserPrograms'; Name = 'Programs' }
    $install = Add-WixNode $programs 'Directory' @{ Id = 'INSTALLFOLDER'; Name = 'WFTools-PC' }
    $menuRoot = Add-WixNode $target 'Directory' @{ Id = 'ProgramMenuFolder' }
    $menu = Add-WixNode $menuRoot 'Directory' @{ Id = 'ApplicationMenuFolder'; Name = 'WFTools-PC' }
    [void](Add-WixNode $target 'Directory' @{ Id = 'DesktopFolder' })
    $feature = Add-WixNode $product 'Feature' @{ Id = 'Complete'; Title = 'WFTools-PC'; Level = '1'; Absent = 'disallow' }
    $group = Add-WixNode $product 'ComponentGroup' @{ Id = 'ApplicationComponents' }
    [void](Add-WixNode $feature 'ComponentGroupRef' @{ Id = 'ApplicationComponents' })

    $files = [Collections.Generic.List[object]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $Payload -File -Recurse -Force) {
        $files.Add([pscustomobject]@{ Relative = (Get-RelativePath $Payload $file.FullName).Replace('\', '/'); Source = $file.FullName })
    }
    if (-not [string]::IsNullOrWhiteSpace($ExtraVlcSource)) {
        foreach ($file in Get-ChildItem -LiteralPath $ExtraVlcSource -File -Recurse -Force) {
            $files.Add([pscustomobject]@{ Relative = 'app/resources/vlc/' + (Get-RelativePath $ExtraVlcSource $file.FullName).Replace('\', '/'); Source = $file.FullName })
        }
    }
    if (-not @($files | Where-Object { $_.Relative -eq 'WFTools-PC.ico' }).Count) {
        $files.Add([pscustomobject]@{ Relative = 'WFTools-PC.ico'; Source = $iconPath })
    }
    if (-not @($files | Where-Object { $_.Relative -eq 'USER_GUIDE.md' }).Count) {
        $files.Add([pscustomobject]@{ Relative = 'USER_GUIDE.md'; Source = $userGuidePath })
    }
    $directories = @{ '' = $install }
    foreach ($file in ($files | Sort-Object Relative)) {
        $segments = $file.Relative.Split('/')
        $relativeDirectory = ''
        for ($i = 0; $i -lt $segments.Length - 1; $i++) {
            $parentDirectory = $relativeDirectory
            $relativeDirectory = if ($relativeDirectory) { $relativeDirectory + '/' + $segments[$i] } else { $segments[$i] }
            if (-not $directories.ContainsKey($relativeDirectory)) {
                $directories[$relativeDirectory] = Add-WixNode $directories[$parentDirectory] 'Directory' @{ Id = (Get-StableId 'D_' $relativeDirectory); Name = $segments[$i] }
            }
        }
    }
    foreach ($relativeDirectory in ($directories.Keys | Sort-Object)) {
        $directory = $directories[$relativeDirectory]
        $componentId = Get-StableId 'C_' ('directory/' + $relativeDirectory)
        $component = Add-WixNode $directory 'Component' @{ Id = $componentId; Guid = (Get-ComponentGuid ('directory/' + $relativeDirectory)); Win64 = 'yes' }
        # Per-user components require an HKCU key path (ICE38). These values are
        # installer bookkeeping, separate from the only document association.
        [void](Add-WixNode $component 'RegistryValue' @{ Root = 'HKCU'; Key = 'Software\WFTools-PC\Installer\Components'; Name = $componentId; Type = 'string'; Value = $Version; KeyPath = 'yes' })
        [void](Add-WixNode $component 'RemoveFolder' @{ Id = (Get-StableId 'R_' $relativeDirectory); On = 'uninstall' })
        $directoryFiles = @($files | Where-Object {
            $separator = $_.Relative.LastIndexOf('/')
            $parent = if ($separator -lt 0) { '' } else { $_.Relative.Substring(0, $separator) }
            $parent -eq $relativeDirectory
        } | Sort-Object Relative)
        if ($directoryFiles.Count -eq 0) { [void](Add-WixNode $component 'CreateFolder' @{}) }
        foreach ($file in $directoryFiles) {
            $fileAttributes = @{ Id = (Get-StableId 'F_' $file.Relative); Name = [IO.Path]::GetFileName($file.Relative); Source = $file.Source; KeyPath = 'no' }
            if ([IO.Path]::GetExtension($file.Relative) -in @('.exe', '.dll', '.sys', '.ocx')) { $fileAttributes.Checksum = 'yes'; $fileAttributes.DefaultLanguage = '0' }
            [void](Add-WixNode $component 'File' $fileAttributes)
        }
        [void](Add-WixNode $group 'ComponentRef' @{ Id = $componentId })
    }
    $shortcuts = Add-WixNode $install 'Component' @{ Id = 'ApplicationShortcuts'; Guid = (Get-ComponentGuid 'shortcuts'); Win64 = 'yes' }
    [void](Add-WixNode $shortcuts 'RegistryValue' @{ Root = 'HKCU'; Key = 'Software\WFTools-PC\Installer'; Name = 'Shortcuts'; Type = 'integer'; Value = '1'; KeyPath = 'yes' })
    foreach ($shortcut in @(@{ Id = 'DesktopShortcut'; Directory = 'DesktopFolder' }, @{ Id = 'StartMenuShortcut'; Directory = 'ApplicationMenuFolder' })) {
        [void](Add-WixNode $shortcuts 'Shortcut' @{ Id = $shortcut.Id; Directory = $shortcut.Directory; Name = 'WFTools-PC'; Target = ('[INSTALLFOLDER]' + $LauncherName); WorkingDirectory = 'INSTALLFOLDER'; Icon = 'ApplicationIcon'; IconIndex = '0'; Advertise = 'no' })
    }
    [void](Add-WixNode $shortcuts 'RemoveFolder' @{ Id = 'RemoveApplicationMenu'; Directory = 'ApplicationMenuFolder'; On = 'uninstall' })
    # RemoveFolder only removes an empty directory. Other installed programs are
    # never touched; this satisfies per-user directory cleanup validation (ICE64).
    [void](Add-WixNode $shortcuts 'RemoveFolder' @{ Id = 'RemoveEmptyUserPrograms'; Directory = 'UserPrograms'; On = 'uninstall' })
    [void](Add-WixNode $group 'ComponentRef' @{ Id = 'ApplicationShortcuts' })
    $association = Add-WixNode $install 'Component' @{ Id = 'WatchOpenWith'; Guid = (Get-ComponentGuid 'open-with'); Win64 = 'yes' }
    [void](Add-WixNode $association 'RegistryValue' @{ Root = 'HKCU'; Key = ('Software\Classes\' + $progId); Type = 'string'; Value = 'WatchMaker watch face (WFTools-PC)'; KeyPath = 'yes' })
    [void](Add-WixNode $association 'RegistryValue' @{ Root = 'HKCU'; Key = ('Software\Classes\' + $progId + '\DefaultIcon'); Type = 'string'; Value = '"[INSTALLFOLDER]WFTools-PC.ico",0' })
    [void](Add-WixNode $association 'RegistryValue' @{ Root = 'HKCU'; Key = ('Software\Classes\' + $progId + '\shell\open\command'); Type = 'string'; Value = ('"[INSTALLFOLDER]' + $LauncherName + '" "%1"') })
    [void](Add-WixNode $association 'RegistryValue' @{ Root = 'HKCU'; Key = 'Software\Classes\.watch\OpenWithProgids'; Name = $progId; Type = 'string'; Value = '' })
    [void](Add-WixNode $group 'ComponentRef' @{ Id = 'WatchOpenWith' })

    $settings = [Xml.XmlWriterSettings]::new(); $settings.Indent = $true; $settings.Encoding = [Text.UTF8Encoding]::new($false)
    $writer = [Xml.XmlWriter]::Create($Destination, $settings)
    try { $script:packageWixDocument.Save($writer) } finally { $writer.Dispose() }
    Assert-WixDefinition $Destination
    return $files.Count
}

function Assert-WixDefinition([string]$Path) {
    $document = [Xml.XmlDocument]::new(); $document.Load($Path)
    $manager = [Xml.XmlNamespaceManager]::new($document.NameTable); $manager.AddNamespace('w', $wixNamespace)
    $package = $document.SelectSingleNode('//w:Package', $manager)
    if ($package.InstallScope -ne 'perUser' -or $package.InstallPrivileges -ne 'limited') { throw 'MSI must be per-user and limited privilege.' }
    $upgrade = $document.SelectSingleNode('//w:MajorUpgrade', $manager)
    if ($upgrade.AllowSameVersionUpgrades -ne 'yes') { throw 'MSI must replace a same-version rebuild instead of installing side by side.' }
    foreach ($value in $document.SelectNodes('//w:RegistryValue', $manager)) {
        if ($value.Root -ne 'HKCU') { throw 'Only HKCU registry rows are permitted.' }
        $key = [string]$value.Key
        $allowed = $key.StartsWith('Software\WFTools-PC\Installer', [StringComparison]::OrdinalIgnoreCase) -or
            $key.StartsWith(('Software\Classes\' + $progId), [StringComparison]::OrdinalIgnoreCase) -or
            $key.Equals('Software\Classes\.watch\OpenWithProgids', [StringComparison]::OrdinalIgnoreCase)
        if (-not $allowed) { throw "Unexpected registry key: $key" }
        if ($key -eq 'Software\Classes\.watch\OpenWithProgids' -and $value.Name -ne $progId) { throw 'Unexpected OpenWithProgids value.' }
    }
    if ($document.SelectNodes('//w:CustomAction|//w:Extension|//w:ProgId|//w:RegistryKey', $manager).Count -ne 0) { throw 'Unexpected custom action or implicit association authoring.' }
    if ($document.SelectNodes('//w:Shortcut', $manager).Count -ne 2) { throw 'Exactly one desktop and one Start Menu shortcut are required.' }
    $schemas = [Xml.Schema.XmlSchemaSet]::new(); [void]$schemas.Add($wixNamespace, (Join-Path $WixDirectory 'doc/wix.xsd'))
    $document.Schemas = $schemas
    $errors = [Collections.Generic.List[string]]::new()
    $document.Validate([Xml.Schema.ValidationEventHandler]{ param($sender, $eventArgs) $errors.Add($eventArgs.Message) })
    if ($errors.Count) { throw ('WiX XML schema errors: ' + ($errors -join '; ')) }
}

function Get-MsiRows($Database, [string]$Table, [string[]]$Columns) {
    $query = 'SELECT ' + (($Columns | ForEach-Object { '`' + $_ + '`' }) -join ',') + ' FROM `' + $Table + '`'
    $view = $Database.OpenView($query)
    try {
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $row = [ordered]@{}
                for ($i = 0; $i -lt $Columns.Count; $i++) {
                    $row[$Columns[$i]] = $record.GetType().InvokeMember('StringData', [Reflection.BindingFlags]::GetProperty, $null, $record, @($i + 1))
                }
                [pscustomobject]$row
            } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) }
        }
    } finally { [void]$view.Close(); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
}

function Assert-MsiTables([string]$Path) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $null
    try {
        $database = $installer.OpenDatabase($Path, 0)
        $properties = @{}; foreach ($property in Get-MsiRows $database 'Property' @('Property', 'Value')) { $properties[$property.Property] = $property.Value }
        if ($properties.ContainsKey('ALLUSERS') -and $properties.ALLUSERS -notin @('', '2')) { throw 'MSI unexpectedly requests a machine-wide install.' }
        if ($properties.ContainsKey('ALLUSERS') -and $properties.ALLUSERS -eq '2' -and $properties.MSIINSTALLPERUSER -ne '1') { throw 'Dual-scope MSI is not fixed to per-user.' }
        $registry = @(Get-MsiRows $database 'Registry' @('Root', 'Key', 'Name', 'Value'))
        foreach ($row in $registry) {
            if ($row.Root -ne '1') { throw "MSI registry row is not HKCU: $($row.Key)" }
            if (-not ($row.Key.StartsWith('Software\WFTools-PC\Installer', [StringComparison]::OrdinalIgnoreCase) -or
                $row.Key.StartsWith(('Software\Classes\' + $progId), [StringComparison]::OrdinalIgnoreCase) -or $row.Key -eq 'Software\Classes\.watch\OpenWithProgids')) { throw "Unexpected MSI registry row: $($row.Key)" }
        }
        $candidate = @($registry | Where-Object { $_.Key -eq 'Software\Classes\.watch\OpenWithProgids' -and $_.Name -eq $progId })
        $command = @($registry | Where-Object { $_.Key -eq ('Software\Classes\' + $progId + '\shell\open\command') })
        if ($candidate.Count -ne 1 -or $command.Count -ne 1 -or $command[0].Value -ne ('"[INSTALLFOLDER]' + $LauncherName + '" "%1"')) { throw 'MSI Open With registration differs from the approved contract.' }
        $shortcuts = @(Get-MsiRows $database 'Shortcut' @('Shortcut', 'Directory_', 'Target'))
        if ($shortcuts.Count -ne 2 -or @($shortcuts | Where-Object { $_.Directory_ -eq 'DesktopFolder' }).Count -ne 1 -or @($shortcuts | Where-Object { $_.Directory_ -eq 'ApplicationMenuFolder' }).Count -ne 1) { throw 'MSI shortcut rows differ from the approved contract.' }
        return [pscustomobject]@{ RegistryRows = $registry.Count; Shortcuts = $shortcuts.Count; PerUser = $true; DefaultAssociationChanged = $false; InstallerExecuted = $false }
    } finally {
        if ($null -ne $database) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    }
}

function Get-JarNoticeDirectoryName([string]$BaseName) {
    # WiX 3 resolves File/@Source through the Win32 path API. Some Compose
    # JAR names plus META-INF notice paths exceed MAX_PATH when kept verbatim.
    # Keep a readable prefix and append a stable suffix so the package remains
    # deterministic while staying below the legacy linker limit.
    if ($BaseName.Length -le 40) { return $BaseName }
    $bytes = [Text.Encoding]::UTF8.GetBytes($BaseName.ToLowerInvariant())
    $stream = [IO.MemoryStream]::new($bytes, $false)
    try { $suffix = (Get-StreamHash $stream).Substring(0, 8).ToLowerInvariant() }
    finally { $stream.Dispose() }
    return $BaseName.Substring(0, 31) + '-' + $suffix
}

function Export-JarNotices([string]$Payload) {
    $results = [Collections.Generic.List[object]]::new()
    $root = Join-Path $Payload 'licenses/jars'
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $Payload 'app') -Filter '*.jar' -File -Recurse) {
        $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        $extracted = [Collections.Generic.List[string]]::new()
        $noticeDirectory = Get-JarNoticeDirectoryName $jar.BaseName
        try {
            foreach ($entry in $archive.Entries) {
                if ($entry.FullName -notmatch '(^|/)(LICENSE|LICENCE|COPYING|NOTICE)([._-][^/]*)?$|^META-INF/maven/.+/pom\.xml$') { continue }
                if ($entry.FullName -match '(^|/)\.\.(/|$)|[\\:]' -or $entry.Length -gt 10MB) { throw "Unsafe or oversized JAR notice: $($jar.Name)/$($entry.FullName)" }
                $destination = Join-Path (Join-Path $root $noticeDirectory) $entry.FullName
                Assert-UnderDirectory $destination $root
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
                $inputStream = $entry.Open(); $outputStream = [IO.File]::Create($destination)
                try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
                $extracted.Add($entry.FullName)
            }
        } finally { $archive.Dispose() }
        $results.Add([pscustomobject]@{ Jar = $jar.Name; NoticeDirectory = $noticeDirectory; Sha256 = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash; Notices = $extracted.ToArray() })
    }
    return $results.ToArray()
}

# Preflight: never mutate the input app image or invoke an installer.
if ($VerifyMsi) {
    Assert-UnderDirectory $VerifyMsi $artifactRoot
    Assert-MsiTables $VerifyMsi | ConvertTo-Json -Depth 7
    return
}
Assert-UnderDirectory $OutputDirectory $artifactRoot
$parts = $Version.Split('.') | ForEach-Object { [int]$_ }
if ($parts[0] -gt 255 -or $parts[1] -gt 255 -or $parts[2] -gt 65535) { throw 'Version exceeds MSI numeric limits.' }
foreach ($file in @($iconPath, $userGuidePath, (Join-Path $packagingRoot 'README_WINDOWS.md'), (Join-Path $WixDirectory 'candle.exe'), (Join-Path $WixDirectory 'light.exe'), (Join-Path $VlcDirectory 'COPYING.txt'))) {
    if (-not [IO.File]::Exists($file)) { throw "Missing packaging input: $file" }
}
Assert-X64Pe (Join-Path $AppImage $LauncherName)
Assert-X64Pe (Join-Path $AppImage 'runtime/bin/server/jvm.dll')
if (-not [IO.Directory]::Exists((Join-Path $AppImage 'runtime/legal'))) { throw 'Input app image must retain runtime/legal.' }
$verifiedVlc = @(Assert-VlcSource)
$pluginCache = Join-Path $VlcDirectory 'plugins/plugins.dat'
if (-not [IO.File]::Exists($pluginCache) -or (Get-Item -LiteralPath $pluginCache).Length -eq 0) {
    if ($ValidateOnly -or $GenerateWixOnly) { throw 'VLC plugins/plugins.dat is missing; generate it before packaging.' }
    & (Join-Path $VlcDirectory 'vlc-cache-gen.exe') (Join-Path $VlcDirectory 'plugins')
    if ($LASTEXITCODE -ne 0 -or -not [IO.File]::Exists($pluginCache) -or (Get-Item -LiteralPath $pluginCache).Length -eq 0) { throw 'VLC plugin cache generation failed.' }
}
if ($ValidateOnly) {
    [pscustomobject]@{ Mode = 'ValidateOnly'; AppImage = $AppImage; Version = $Version; VlcArchiveSha256 = $VlcArchiveSha256; VerifiedVlcBinaries = $verifiedVlc; PluginCacheSha256 = (Get-FileHash -LiteralPath $pluginCache -Algorithm SHA256).Hash; InstallerExecuted = $false } | ConvertTo-Json -Depth 5
    return
}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$stage = Join-Path $OutputDirectory ('.packaging/' + $runId)
Assert-UnderDirectory $stage $artifactRoot
[void][IO.Directory]::CreateDirectory($stage)
$wxs = Join-Path $stage 'WFTools-PC.wxs'
if ($GenerateWixOnly) {
    $count = New-WixDefinition $AppImage $wxs $VlcDirectory
    [pscustomobject]@{ Mode = 'GenerateWixOnly'; Definition = $wxs; Files = $count; XmlSchemaValidated = $true; InstallerExecuted = $false } | ConvertTo-Json
    return
}
$portable = Join-Path $OutputDirectory ('WFTools-PC-' + $Version + '-windows-x64-portable.zip')
$msi = Join-Path $OutputDirectory ('WFTools-PC-' + $Version + '-windows-x64.msi')
$checksums = Join-Path $OutputDirectory ('WFTools-PC-' + $Version + '-SHA256SUMS.txt')
$reportPath = Join-Path $OutputDirectory ('WFTools-PC-' + $Version + '-PACKAGING.json')
foreach ($output in @($portable, $msi, $checksums, $reportPath)) { if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite release artifact: $output. Use a new artifacts subdirectory." } }
$payload = Join-Path $stage 'WFTools-PC'
Copy-PreservedTree $AppImage $payload
$nativeDestination = Join-Path $payload 'app/resources/vlc'
if (Test-Path -LiteralPath $nativeDestination) { throw 'Input app image already contains VLC; provide a fresh createDistributable image.' }
Copy-PreservedTree $VlcDirectory $nativeDestination
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $VlcDirectory 'plugins') -File -Recurse) {
    $copy = Join-Path $nativeDestination (Get-RelativePath $VlcDirectory $file.FullName)
    if ((Get-Item -LiteralPath $copy).LastWriteTimeUtc.Ticks -ne $file.LastWriteTimeUtc.Ticks) { throw "VLC plugin mtime was not preserved: $($file.Name)" }
}
Copy-PreservedTree (Join-Path $packagingRoot 'licenses') (Join-Path $payload 'licenses')
[IO.File]::Copy($iconPath, (Join-Path $payload 'WFTools-PC.ico'), $true)
[IO.File]::Copy((Join-Path $packagingRoot 'README_WINDOWS.md'), (Join-Path $payload 'README_WINDOWS.md'), $true)
[IO.File]::Copy($userGuidePath, (Join-Path $payload 'USER_GUIDE.md'), $true)
$configuration = Join-Path $payload ('app/' + [IO.Path]::GetFileNameWithoutExtension($LauncherName) + '.cfg')
if (-not [IO.File]::Exists($configuration)) { throw "Missing native launcher configuration: $configuration" }
$lines = [Collections.Generic.List[string]]::new()
$lines.AddRange([string[]][IO.File]::ReadAllLines($configuration))
$resourceOption = 'java-options=-Dcompose.application.resources.dir=$APPDIR\resources'
$optionIndex = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith('java-options=-Dcompose.application.resources.dir=')) { $optionIndex = $i; break } }
if ($optionIndex -ge 0) { $lines[$optionIndex] = $resourceOption }
else {
    $section = $lines.IndexOf('[JavaOptions]')
    if ($section -lt 0) { $lines.Add('[JavaOptions]'); $lines.Add($resourceOption) }
    else { $lines.Insert($section + 1, $resourceOption) }
}
[IO.File]::WriteAllLines($configuration, $lines, [Text.UTF8Encoding]::new($false))
$jarNotices = @(Export-JarNotices $payload)
$revision = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Out-String).Trim()
$dirty = -not [string]::IsNullOrWhiteSpace((& git -C $repositoryRoot status --porcelain 2>$null | Out-String))
$manifest = [ordered]@{
    Application = 'WFTools-PC'; Version = $Version; Architecture = 'x64'; CreatedUtc = [DateTime]::UtcNow.ToString('o')
    SourceRevision = $revision; SourceWorkingTreeHadChanges = $dirty
    JavaRelease = [IO.File]::ReadAllText((Join-Path $payload 'runtime/release'))
    VlcArchive = [IO.Path]::GetFileName($VlcArchive); VlcArchiveSha256 = $VlcArchiveSha256; VerifiedVlcBinaries = $verifiedVlc
    VlcPluginCacheSha256 = (Get-FileHash -LiteralPath (Join-Path $nativeDestination 'plugins/plugins.dat') -Algorithm SHA256).Hash
    VlcPluginModificationTimesPreserved = $true; Jars = $jarNotices
    UpstreamWftoolsLicense = 'Not supplied in the checked upstream snapshot; see licenses/UPSTREAM-LICENSE-STATUS.md.'
    Installer = @{ Scope = 'perUser'; Directory = '%LOCALAPPDATA%\Programs\WFTools-PC'; OpenWithProgId = $progId; ChangesWatchDefault = $false; PreservesDraftWorkspace = $true }
}
[IO.File]::WriteAllText((Join-Path $payload 'PACKAGE-MANIFEST.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if ($PreparePortableOnly) {
    [pscustomobject]@{ Mode = 'PreparePortableOnly'; Payload = $payload; Manifest = (Join-Path $payload 'PACKAGE-MANIFEST.json'); InstallerExecuted = $false } | ConvertTo-Json
    return
}
$fileCount = New-WixDefinition $payload $wxs
$wixObject = Join-Path $stage 'WFTools-PC.wixobj'
$stagedMsi = Join-Path $stage 'WFTools-PC.msi'
$candleOutput = & (Join-Path $WixDirectory 'candle.exe') '-nologo' '-arch' 'x64' '-out' $wixObject $wxs 2>&1
$candleExit = $LASTEXITCODE
$candleOutput | Set-Content -LiteralPath (Join-Path $stage 'candle.log') -Encoding UTF8
if ($candleExit -ne 0) { throw "WiX candle failed ($candleExit); inspect $stage\candle.log" }
# ICE91 describes per-user files not following ALLUSERS; this installer is
# intentionally perUser/limited and cannot be switched to a per-machine install.
$lightOutput = & (Join-Path $WixDirectory 'light.exe') '-nologo' '-sice:ICE91' '-out' $stagedMsi '-pdbout' (Join-Path $stage 'WFTools-PC.wixpdb') $wixObject 2>&1
$lightExit = $LASTEXITCODE
$lightOutput | Set-Content -LiteralPath (Join-Path $stage 'light.log') -Encoding UTF8
if ($lightExit -ne 0) { throw "WiX light failed ($lightExit); inspect $stage\light.log" }
$msiValidation = Assert-MsiTables $stagedMsi
$stagedZip = Join-Path $stage 'portable.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($payload, $stagedZip, [IO.Compression.CompressionLevel]::Optimal, $true)
$zip = [IO.Compression.ZipFile]::OpenRead($stagedZip)
try {
    foreach ($required in @(('WFTools-PC/' + $LauncherName), 'WFTools-PC/runtime/bin/server/jvm.dll', 'WFTools-PC/app/resources/vlc/libvlc.dll', 'WFTools-PC/app/resources/vlc/plugins/plugins.dat', 'WFTools-PC/README_WINDOWS.md', 'WFTools-PC/USER_GUIDE.md', 'WFTools-PC/PACKAGE-MANIFEST.json')) {
        if ($null -eq $zip.GetEntry($required)) { throw "Portable ZIP is missing: $required" }
    }
} finally { $zip.Dispose() }
[IO.File]::Move($stagedZip, $portable)
[IO.File]::Move($stagedMsi, $msi)
$artifacts = @($portable, $msi) | ForEach-Object { [pscustomobject]@{ File = [IO.Path]::GetFileName($_); Sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash; Bytes = (Get-Item -LiteralPath $_).Length } }
[IO.File]::WriteAllLines($checksums, [string[]]@($artifacts | ForEach-Object { $_.Sha256 + '  ' + $_.File }), [Text.UTF8Encoding]::new($false))
$report = [ordered]@{ Artifacts = $artifacts; Files = $fileCount; VlcSource = $verifiedVlc; MsiValidation = $msiValidation; StagingDirectory = $stage; Definition = $wxs; InstallerExecuted = $false }
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
$report | ConvertTo-Json -Depth 7
