Unicode True
RequestExecutionLevel user
!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

!ifndef PRODUCT_NAME
!define PRODUCT_NAME "ClipFlow"
!endif
!define INSTALL_KEY "Software\${PRODUCT_NAME}\Installer"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}"

Name "${PRODUCT_NAME}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
InstallDirRegKey HKCU "${INSTALL_KEY}" "InstallDir"
SetCompressor /SOLID lzma
ShowInstDetails show
Var UpdateMode
Var Attempts
Var Mutex

!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipUpdatePage
!insertmacro MUI_PAGE_WELCOME
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipUpdatePage
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipUpdatePage
!define MUI_FINISHPAGE_RUN "$INSTDIR\ClipFlow.exe"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Korean"

Function .onInit
    SetShellVarContext current
    ; Relaunch as a fresh onefile app, not a worker using the exiting app's temp directory.
    System::Call 'kernel32::SetEnvironmentVariableW(w "PYINSTALLER_RESET_ENVIRONMENT", w "1")'
    System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Local\${PRODUCT_NAME}-Installer") p.r0 ?e'
    Pop $1
    StrCpy $Mutex $0
    ${If} $1 = 183
        SetErrorLevel 1
        Abort
    ${EndIf}
    ${GetParameters} $0
    ClearErrors
    ${GetOptions} $0 "/UPDATE" $1
    ${IfNot} ${Errors}
        StrCpy $UpdateMode "1"
        SetAutoClose true
    ${EndIf}
FunctionEnd

Function SkipUpdatePage
    ${If} $UpdateMode == "1"
        Abort
    ${EndIf}
FunctionEnd

Section "Install"
    SetOutPath "$INSTDIR"
    ; Stage first. A failed extraction must not truncate the installed executable.
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /oname=ClipFlow.exe "${APP_EXE}"
    ClearErrors
    CopyFiles /SILENT "$PLUGINSDIR\ClipFlow.exe" "$INSTDIR\ClipFlow.update"
    IfErrors install_failed
    StrCpy $Attempts 0
retry_replace:
    ClearErrors
    Delete "$INSTDIR\ClipFlow.previous"
    IfFileExists "$INSTDIR\ClipFlow.exe" 0 publish
    Rename "$INSTDIR\ClipFlow.exe" "$INSTDIR\ClipFlow.previous"
    IfErrors wait_for_exit publish
wait_for_exit:
    IntOp $Attempts $Attempts + 1
    ${If} $Attempts < 30
        Sleep 1000
        Goto retry_replace
    ${EndIf}
    Goto install_failed
publish:
    ClearErrors
    Rename "$INSTDIR\ClipFlow.update" "$INSTDIR\ClipFlow.exe"
    ${If} ${Errors}
        Rename "$INSTDIR\ClipFlow.previous" "$INSTDIR\ClipFlow.exe"
        Goto install_failed
    ${EndIf}
    Delete "$INSTDIR\ClipFlow.previous"
    SetOutPath "$INSTDIR"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    WriteRegStr HKCU "${INSTALL_KEY}" "InstallDir" "$INSTDIR"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${PRODUCT_NAME}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
    CreateShortcut "$SMPROGRAMS\${PRODUCT_NAME}.lnk" "$INSTDIR\ClipFlow.exe"
    Goto installed
install_failed:
    Delete "$INSTDIR\ClipFlow.update"
    SetErrorLevel 2
    IfSilent +2
    MessageBox MB_OK|MB_ICONSTOP "Close ClipFlow and retry. The update could not replace the application."
    Abort
installed:
SectionEnd

Function .onInstSuccess
    ${If} $UpdateMode == "1"
        IfSilent done
        Exec '"$INSTDIR\ClipFlow.exe"'
    ${EndIf}
done:
FunctionEnd

Section "Uninstall"
    SetShellVarContext current
    Delete "$INSTDIR\ClipFlow.exe"
    IfErrors uninstall_failed
    Delete "$INSTDIR\Uninstall.exe"
    Delete "$SMPROGRAMS\${PRODUCT_NAME}.lnk"
    DeleteRegKey HKCU "${INSTALL_KEY}"
    DeleteRegKey HKCU "${UNINSTALL_KEY}"
    ; Never recursively remove this folder: it may contain user downloads.
    RMDir "$INSTDIR"
    Goto uninstalled
uninstall_failed:
    SetErrorLevel 2
    Abort
uninstalled:
SectionEnd
