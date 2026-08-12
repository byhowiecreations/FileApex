#ifndef AppVersion
#define AppVersion "0.6.26a"
#endif

[Setup]
AppId={{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}
AppName=FileApex
AppVersion={#AppVersion}
AppPublisher=ByHowieCreations
AppComments=A local-first P2P file sharing app
AppMutex=FileApex
DefaultDirName={autopf}\FileApex
DefaultGroupName=FileApex
UninstallDisplayIcon={app}\FileApex.exe
Compression=lzma2/ultra64
SolidCompression=yes
OutputDir=..\composeApp\build\compose\binaries\main-release\exe
OutputBaseFilename=FileApex-v{#AppVersion}
SetupIconFile=..\composeApp\icons\FileApex.ico
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopshortcut"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked
Name: "sendtoshortcut"; Description: "Add FileApex to Windows 'Send to' right-click menu"; GroupDescription: "Integration:"

[Files]
Source: "..\composeApp\build\compose\binaries\main-release\app\FileApex\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "FileApexLauncher.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "FileApexBootstrap.ps1"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\FileApex"; Filename: "{app}\FileApexLauncher.cmd"; IconFilename: "{app}\FileApex.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\FileApex"; Filename: "{app}\FileApexLauncher.cmd"; IconFilename: "{app}\FileApex.exe"; Tasks: desktopshortcut; WorkingDir: "{app}"
Name: "{usersendto}\FileApex"; Filename: "{app}\FileApexLauncher.cmd"; IconFilename: "{app}\FileApex.exe"; Tasks: sendtoshortcut; WorkingDir: "{app}"

[Registry]
Root: HKA; Subkey: "Software\Classes\*\shell\FileApex"; ValueType: string; ValueData: "Send with FileApex"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\*\shell\FileApex\command"; ValueType: string; ValueData: """{app}\FileApexLauncher.cmd"" ""%1"""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\SystemFileAssociations\*\shell\FileApex"; ValueType: string; ValueData: "Send with FileApex"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\SystemFileAssociations\*\shell\FileApex\command"; ValueType: string; ValueData: """{app}\FileApexLauncher.cmd"" ""%1"""; Flags: uninsdeletekey

[Run]
Filename: "{app}\FileApexLauncher.cmd"; Description: "{cm:LaunchProgram,FileApex}"; Flags: nowait postinstall skipifsilent

[Code]
function NeedsVCRedist(): Boolean;
var
  Installed: Cardinal;
  SysDir: String;
begin
  Result := True;
  SysDir := ExpandConstant('{sys}');
  if FileExists(AddBackslash(SysDir) + 'vcruntime140.dll') and
     FileExists(AddBackslash(SysDir) + 'vcruntime140_1.dll') and
     FileExists(AddBackslash(SysDir) + 'msvcp140.dll') then
  begin
    if RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\X64', 'Installed', Installed) and (Installed = 1) then
      Result := False;
  end;
end;
