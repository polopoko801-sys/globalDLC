' GlobalDLC App - lokalny menedzer UID + status klienta.
' Uruchamia okno BEZ terminala. Aplikacja startuje z KOPII w %TEMP%, wiec nie trzyma
' injector\GlobalDLCApp.jar - Build.bat moze go przebudowac, kiedy okno jest otwarte.
Option Explicit
Dim fso, sh, base, inj, tmp
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
inj  = base & "\injector"

If Not fso.FileExists(inj & "\GlobalDLCApp.jar") Then
    sh.Run """" & inj & "\Build.bat"" silent", 0, True
End If

tmp = sh.ExpandEnvironmentStrings("%TEMP%") & "\GlobalDLCApp-run.jar"
On Error Resume Next
fso.CopyFile inj & "\GlobalDLCApp.jar", tmp, True
If Err.Number <> 0 Then tmp = inj & "\GlobalDLCApp.jar"
On Error GoTo 0

sh.CurrentDirectory = inj
sh.Run "javaw -jar """ & tmp & """", 0, False
