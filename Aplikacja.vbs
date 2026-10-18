' ThunderHack Recode - Aplikacja (Injector dla kupujacego)
' Uruchamia okno BEZ terminala. Buduje po cichu tylko jesli brak .jar.
Option Explicit
Dim fso, sh, base, inj
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
inj  = base & "\injector"

If Not fso.FileExists(inj & "\ThunderInjector.jar") Then
    ' 0 = ukryte okno, True = czekaj az zbuduje
    sh.Run """" & inj & "\Build.bat"" silent", 0, True
End If

sh.CurrentDirectory = inj
' javaw nie tworzy konsoli; 0 = brak okna launchera
sh.Run "javaw -jar ""ThunderInjector.jar""", 0, False
