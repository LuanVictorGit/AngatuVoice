; Per-user installer: no administrator rights, shortcuts, optional autostart, clean uninstall.
#ifndef AppVersion
  #define AppVersion "1.5.1"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\image\AngatuVoice"
#endif

[Setup]
AppId={{6C1D6B0E-8B3E-4B7A-9D55-A47A7C0E5A11}
AppName=Angatu Voice
AppVersion={#AppVersion}
AppPublisher=Angatu Sistemas
AppPublisherURL=https://angatusistemas.com.br
DefaultDirName={localappdata}\Programs\AngatuVoice
DefaultGroupName=Angatu Voice
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=AngatuVoice-Setup
SetupIconFile=app.ico
UninstallDisplayIcon={app}\AngatuVoice.exe
Compression=lzma2/fast
SolidCompression=no
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
RestartApplications=no
DisableProgramGroupPage=yes

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "autostart"; Description: "{cm:AutoStartProgram,Angatu Voice}"; GroupDescription: "{cm:AutoStartProgramGroupDescription}"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Angatu Voice"; Filename: "{app}\AngatuVoice.exe"
Name: "{autodesktop}\Angatu Voice"; Filename: "{app}\AngatuVoice.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "AngatuVoice"; ValueData: """{app}\AngatuVoice.exe"" --autostart"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\AngatuVoice.exe"; Description: "{cm:LaunchProgram,Angatu Voice}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\.rollback"
Type: filesandordirs; Name: "{app}"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'AngatuVoice');
    if MsgBox('Apagar tamb'#$E9'm as mensagens, configura'#$E7#$F5'es e a identidade deste computador?', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
    begin
      DelTree(ExpandConstant('{userappdata}\AngatuVoice'), True, True, True);
      DelTree(ExpandConstant('{localappdata}\AngatuVoice'), True, True, True);
    end;
  end;
end;