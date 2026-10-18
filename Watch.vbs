' ThunderHack Recode - ThunderWatch (kto uzywa klienta, haslo 2137)
' Uruchamia okno BEZ terminala.
Option Explicit
Dim fso, sh, base, inj
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
inj  = base & "\injector"

If Not fso.FileExists(inj & "\ThunderWatch.jar") Then
    sh.Run """" & inj & "\Build.bat"" silent", 0, True
End If

sh.CurrentDirectory = inj
sh.Run "javaw -jar ""ThunderWatch.jar""", 0, False
