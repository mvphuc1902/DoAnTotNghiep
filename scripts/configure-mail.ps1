# Run from a local PowerShell terminal. No password is printed or sent to chat.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configDir = Join-Path $projectRoot 'config'
$configPath = Join-Path $configDir 'mail-local.properties'
Write-Host 'Sender: maivinhphucceo2103@gmail.com'
Write-Host 'Enter the Google App Password for this sender (not your regular Gmail password).'
$securePassword = Read-Host 'Gmail App Password' -AsSecureString
$pointer = [IntPtr]::Zero
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $appPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) -replace '\s', ''
    if ([string]::IsNullOrWhiteSpace($appPassword)) {
        throw 'No App Password entered. Nothing was saved. Create a Gmail App Password, then run this script again.'
    }
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    if (-not (Test-Path -LiteralPath $configPath)) {
        [IO.File]::WriteAllText($configPath, '')
    }
    # Change only the access rules. Setting the owner can require extra Windows privileges.
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')))
    $system = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($system, 'FullControl', 'Allow')))
    $configFile = Get-Item -LiteralPath $configPath
    if ($PSVersionTable.PSEdition -eq 'Core') {
        [System.IO.FileSystemAclExtensions]::SetAccessControl($configFile, $acl)
    } else {
        $configFile.SetAccessControl($acl)
    }
    $lines = @(
        'erp.mail.host=smtp.gmail.com',
        'erp.mail.port=587',
        'erp.mail.ssl=false',
        'erp.mail.from=maivinhphucceo2103@gmail.com',
        'erp.mail.username=maivinhphucceo2103@gmail.com',
        ('erp.mail.password=' + $appPassword.Replace('\', '\\'))
    )
    [IO.File]::WriteAllLines($configPath, $lines)
    Write-Host 'Saved locally. SMTP authentication has not yet been verified.'
    Write-Host 'Restart the Spring Boot application from the project directory.'
    Write-Host 'Then open Payroll -> View -> Email payslip, verify the recipient, and click Send to employee.'
    Write-Host 'Existing ERP_MAIL_* environment variables override this local file; remove stale overrides in the run configuration.'
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $appPassword = $null
    $lines = $null
    $securePassword.Dispose()
}