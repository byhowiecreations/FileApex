#ifndef AppVersion
#define AppVersion "0.6.26b"
#endif
#ifndef LZMANumBlockThreads
#define LZMANumBlockThreads 6
#endif
#ifndef CompressionThreads
#define CompressionThreads 6
#endif

[Setup]
AppId={{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}
AppName=FileApex
AppVersion={#AppVersion}
AppPublisher=ByHowieCreations
AppComments=A local-first P2P file sharing app
AppMutex=FileApex
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName=FileApex
UninstallDisplayIcon={app}\FileApex.exe
Compression=lzma2/ultra64
SolidCompression=yes
LZMANumBlockThreads={#LZMANumBlockThreads}
CompressionThreads={#CompressionThreads}
OutputDir=..\composeApp\build\compose\binaries\main-release\exe
OutputBaseFilename=FileApex-v{#AppVersion}
SetupIconFile=..\composeApp\icons\FileApex.ico
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
ChangesEnvironment=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopshortcut"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked
Name: "sendtoshortcut"; Description: "Add FileApex to Windows 'Send to' right-click menu"; GroupDescription: "Integration:"

[Files]
Source: "..\composeApp\build\compose\binaries\main-release\app\FileApex\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "bin\fileapex.cmd"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\fileapex"; DestDir: "{app}\bin"; Flags: ignoreversion

[Icons]
Name: "{group}\FileApex"; Filename: "{app}\FileApex.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\FileApex"; Filename: "{app}\FileApex.exe"; Tasks: desktopshortcut; WorkingDir: "{app}"
Name: "{usersendto}\FileApex"; Filename: "{app}\FileApex.exe"; Tasks: sendtoshortcut; WorkingDir: "{app}"

[Registry]
Root: HKA; Subkey: "Software\FileApex"; ValueType: string; ValueName: "InstallDir"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\FileApex"; ValueType: string; ValueName: "RuntimeDir"; ValueData: "{app}\runtime"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\FileApex"; ValueType: string; ValueName: "Executable"; ValueData: "{app}\FileApex.exe"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\FileApex.exe"; ValueType: string; ValueName: ""; ValueData: "{app}\FileApex.exe"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\FileApex.exe"; ValueType: string; ValueName: "Path"; ValueData: "{app};{app}\runtime\bin;{app}\runtime\bin\server"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\fileapex.cmd"; ValueType: string; ValueName: ""; ValueData: "{app}\bin\fileapex.cmd"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\*\shell\FileApex"; ValueType: string; ValueData: "Send with FileApex"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\*\shell\FileApex"; ValueType: string; ValueName: "Icon"; ValueData: "{app}\FileApex.exe"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\*\shell\FileApex\command"; ValueType: string; ValueData: """{app}\FileApex.exe"" ""%1"""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Directory\shell\FileApex"; ValueType: string; ValueData: "Send with FileApex"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Directory\shell\FileApex"; ValueType: string; ValueName: "Icon"; ValueData: "{app}\FileApex.exe"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Directory\shell\FileApex\command"; ValueType: string; ValueData: """{app}\FileApex.exe"" ""%1"""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\SystemFileAssociations\*\shell\FileApex"; ValueType: string; ValueData: "Send with FileApex"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\SystemFileAssociations\*\shell\FileApex"; ValueType: string; ValueName: "Icon"; ValueData: "{app}\FileApex.exe"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\SystemFileAssociations\*\shell\FileApex\command"; ValueType: string; ValueData: """{app}\FileApex.exe"" ""%1"""; Flags: uninsdeletekey

[Run]
Filename: "{app}\FileApex.exe"; Description: "{cm:LaunchProgram,FileApex}"; Flags: nowait postinstall skipifsilent

[Code]
function IsUnsafeJavaPath(PathStr: String): Boolean;
begin
  Result := (Pos('!', PathStr) > 0) or (Pos('#', PathStr) > 0);
end;

function GetDefaultInstallDir(Param: String): String;
var
  PrevDir: String;
  UserDir: String;
begin
  // 1. Check if previous installation path is registered in Software\FileApex
  if RegQueryStringValue(HKA, 'Software\FileApex', 'InstallDir', PrevDir) and (PrevDir <> '') then
  begin
    if (not IsUnsafeJavaPath(PrevDir)) and DirExists(PrevDir) then
    begin
      Result := PrevDir;
      Exit;
    end;
  end;

  // 2. Check if previous uninstall location exists and is valid
  if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1', 'InstallLocation', PrevDir) and (PrevDir <> '') then
  begin
    if (not IsUnsafeJavaPath(PrevDir)) and DirExists(PrevDir) then
    begin
      Result := PrevDir;
      Exit;
    end;
  end;

  // 3. Fallback to clean path: commonappdata if userappdata has '!' or '#', else autopf
  UserDir := ExpandConstant('{userappdata}');
  if IsUnsafeJavaPath(UserDir) then
    Result := ExpandConstant('{commonappdata}\FileApex')
  else
    Result := ExpandConstant('{autopf}\FileApex');
end;

procedure UninstallAndRemoveDirectory(PathDir: String);
var
  UninstallerExe: String;
  ResultCode: Integer;
begin
  if (PathDir <> '') and DirExists(PathDir) then
  begin
    UninstallerExe := AddBackslash(PathDir) + 'unins000.exe';
    if FileExists(UninstallerExe) then
    begin
      Exec(UninstallerExe, '/SILENT /NORESTART /SUPPRESSMSGBOXES', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
    UninstallerExe := AddBackslash(PathDir) + 'unins001.exe';
    if FileExists(UninstallerExe) then
    begin
      Exec(UninstallerExe, '/SILENT /NORESTART /SUPPRESSMSGBOXES', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
    DelTree(PathDir, True, True, True);
  end;
end;

procedure CleanupLegacyCorruptInstallations();
var
  RegPath: String;
  TargetAppDir: String;
  UserAppDir1, UserAppDir2, UserAppDir3: String;
begin
  TargetAppDir := ExpandConstant('{app}');

  // 1. HKCU Uninstall Key check
  if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1', 'InstallLocation', RegPath) then
  begin
    if (RegPath <> '') and (CompareText(RegPath, TargetAppDir) <> 0) then
    begin
      UninstallAndRemoveDirectory(RegPath);
      RegDeleteKeyIncludingSubkeys(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1');
    end;
  end;

  // 2. HKLM Uninstall Key check
  if RegQueryStringValue(HKLM, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1', 'InstallLocation', RegPath) then
  begin
    if (RegPath <> '') and (CompareText(RegPath, TargetAppDir) <> 0) then
    begin
      UninstallAndRemoveDirectory(RegPath);
      RegDeleteKeyIncludingSubkeys(HKLM, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1');
    end;
  end;

  // 3. HKLM64 Uninstall Key check
  if RegQueryStringValue(HKLM64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1', 'InstallLocation', RegPath) then
  begin
    if (RegPath <> '') and (CompareText(RegPath, TargetAppDir) <> 0) then
    begin
      UninstallAndRemoveDirectory(RegPath);
      RegDeleteKeyIncludingSubkeys(HKLM64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C4F8A2E-9B1D-4E6A-C3F5-8D2E1A0B9C7F}_is1');
    end;
  end;

  // 4. Scan known per-user corrupt paths (e.g. C:\Users\<user!>\AppData\Local\Programs\FileApex)
  UserAppDir1 := ExpandConstant('{userappdata}\Local\Programs\FileApex');
  if (CompareText(UserAppDir1, TargetAppDir) <> 0) and DirExists(UserAppDir1) then
  begin
    UninstallAndRemoveDirectory(UserAppDir1);
  end;

  UserAppDir2 := ExpandConstant('{userappdata}\FileApex');
  if (CompareText(UserAppDir2, TargetAppDir) <> 0) and DirExists(UserAppDir2) then
  begin
    if FileExists(AddBackslash(UserAppDir2) + 'FileApex.exe') then
    begin
      UninstallAndRemoveDirectory(UserAppDir2);
    end;
  end;

  UserAppDir3 := ExpandConstant('{localappdata}\Programs\FileApex');
  if (CompareText(UserAppDir3, TargetAppDir) <> 0) and DirExists(UserAppDir3) then
  begin
    UninstallAndRemoveDirectory(UserAppDir3);
  end;

  if FileExists(AddBackslash(TargetAppDir) + 'FileApexLauncher.cmd') then
    DeleteFile(AddBackslash(TargetAppDir) + 'FileApexLauncher.cmd');
  if FileExists(AddBackslash(TargetAppDir) + 'FileApexBootstrap.ps1') then
    DeleteFile(AddBackslash(TargetAppDir) + 'FileApexBootstrap.ps1');
end;

procedure AddAppToPath();
var
  Paths: String;
  BinDir, LegacyAppDir: String;
begin
  BinDir := ExpandConstant('{app}\bin');
  LegacyAppDir := ExpandConstant('{app}');
  if RegQueryStringValue(HKA, 'Environment', 'Path', Paths) then
  begin
    // Clean up legacy {app} from PATH if present
    if Pos(';' + Uppercase(LegacyAppDir) + ';', ';' + Uppercase(Paths) + ';') > 0 then
    begin
      Paths := ';' + Paths + ';';
      StringChange(Paths, ';' + LegacyAppDir + ';', ';');
      if (Length(Paths) > 0) and (Paths[1] = ';') then Delete(Paths, 1, 1);
      if (Length(Paths) > 0) and (Paths[Length(Paths)] = ';') then Delete(Paths, Length(Paths), 1);
    end;

    if Pos(';' + Uppercase(BinDir) + ';', ';' + Uppercase(Paths) + ';') = 0 then
    begin
      if (Paths <> '') and (Paths[Length(Paths)] <> ';') then
        Paths := Paths + ';';
      Paths := Paths + BinDir;
      RegWriteStringValue(HKA, 'Environment', 'Path', Paths);
    end
    else
    begin
      RegWriteStringValue(HKA, 'Environment', 'Path', Paths);
    end;
  end
  else
  begin
    RegWriteStringValue(HKA, 'Environment', 'Path', BinDir);
  end;
end;

procedure RemoveAppFromPath();
var
  Paths, NewPaths: String;
  BinDir: String;
  P, L: Integer;
begin
  BinDir := ExpandConstant('{app}\bin');
  if RegQueryStringValue(HKA, 'Environment', 'Path', Paths) then
  begin
    NewPaths := ';' + Paths + ';';
    P := Pos(';' + Uppercase(BinDir) + ';', Uppercase(NewPaths));
    if P > 0 then
    begin
      L := Length(BinDir) + 1;
      Delete(NewPaths, P, L);
      if (Length(NewPaths) > 0) and (NewPaths[1] = ';') then
        Delete(NewPaths, 1, 1);
      if (Length(NewPaths) > 0) and (NewPaths[Length(NewPaths)] = ';') then
        Delete(NewPaths, Length(NewPaths), 1);
      RegWriteStringValue(HKA, 'Environment', 'Path', NewPaths);
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    CleanupLegacyCorruptInstallations();
  end
  else if CurStep = ssPostInstall then
  begin
    AddAppToPath();
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RemoveAppFromPath();
  end;
end;

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
