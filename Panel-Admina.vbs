' ThunderHack Recode - Panel Admina (dla Ciebie, haslo 2137)
' Uruchamia okno BEZ terminala.
Option Explicit
Dim fso, sh, base, inj
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
inj  = base & "\injector"

If Not fso.FileExists(inj & "\ThunderAdmin.jar") Then
    sh.Run """" & inj & "\Build.bat"" silent", 0, True
End If

sh.CurrentDirectory = inj
sh.Run "javaw -jar ""ThunderAdmin.jar""", 0, False
