# Reads the line being sung from the Spotify web player's lyrics view through UI Automation (the accessibility
# tree screen readers use) and prints one tab-separated record every ~50 ms. When no Spotify window shows the
# lyrics, it opens one of its own in the user's Chrome and makes it invisible (see Hide) - the windows the user
# works with are never changed. Records:
#   line   <index>  <line being sung>  <next line>  <1 playing / 0 paused>
#   intro  -1       (empty)            <first line> <1/0>       before the first line
#   nolyrics                                       Spotify open, but no window shows its lyrics view
#   notext                                         this song has no lyrics on Spotify
#   nowindow                                       no Spotify window at all
#   now    <album url>  <label>  <m:ss elapsed>  <m:ss length>  <1/0 playing>   the track playing, ~4x a second
# Nothing is downloaded: the text is exactly what Spotify shows. The index tells two identical lines in a row
# apart (a repeated hook), which the text alone cannot.
#
# In the lyrics view every line carries a state class after its base class: sung, being sung, still to come.
# Sung lines share one state and upcoming lines another, so the line being sung is the one row whose state
# appears exactly once. The class names are obfuscated and change with Spotify updates, so they are never
# hard-coded - only that pattern is relied on.
#
# This file must stay ASCII: Windows PowerShell 5.1 reads it in the ANSI code page.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$AE = [System.Windows.Automation.AutomationElement]
$Scope = [System.Windows.Automation.TreeScope]
$All = [System.Windows.Automation.Condition]::TrueCondition
$walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
$textCond = New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$buttonCond = New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$classCache = New-Object System.Windows.Automation.CacheRequest
$classCache.Add($AE::ClassNameProperty)
$bullet = " $([char]0x2022) "

Add-Type @"
using System; using System.Collections.Generic; using System.Runtime.InteropServices; using System.Text;
public static class LyricsWindow {
    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int i, int v);
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] static extern bool SetLayeredWindowAttributes(IntPtr h, uint key, byte alpha, uint flags);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);

    // A window this script made invisible earlier (an earlier game session): Chrome, with the very styles Hide
    // sets. Reused, so every start does not stack up another hidden window.
    public static IntPtr FindHidden() {
        const int GWL_EXSTYLE = -20, MARK = 0x80 | 0x80000 | 0x20;
        foreach (IntPtr h in Chrome())
            if ((GetWindowLong(h, GWL_EXSTYLE) & MARK) == MARK) return h;
        return IntPtr.Zero;
    }

    public static List<IntPtr> Chrome() {
        var found = new List<IntPtr>();
        EnumWindows((h, l) => {
            var sb = new StringBuilder(64);
            GetClassName(h, sb, 64);
            if (sb.ToString() == "Chrome_WidgetWin_1" && IsWindowVisible(h)) found.Add(h);
            return true;
        }, IntPtr.Zero);
        return found;
    }

    // Out of sight but never covered: Chrome stops exposing (and updating) a page whose window is fully hidden
    // behind others or minimised, so the window cannot simply be buried. Instead it stays on top, invisible -
    // 1/255 opaque, clicks passing straight through, off the taskbar and Alt+Tab - the way game overlays do it.
    // Focus is handed back to whatever had it.
    public static void Hide(IntPtr h, IntPtr restore) {
        const int GWL_EXSTYLE = -20, WS_EX_TOOLWINDOW = 0x80, WS_EX_APPWINDOW = 0x40000,
                WS_EX_LAYERED = 0x80000, WS_EX_TRANSPARENT = 0x20;
        const uint NOACTIVATE = 0x10, LWA_ALPHA = 0x2;
        IntPtr topmost = new IntPtr(-1);
        int ex = GetWindowLong(h, GWL_EXSTYLE);
        SetWindowLong(h, GWL_EXSTYLE, (ex | WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_TRANSPARENT) & ~WS_EX_APPWINDOW);
        SetLayeredWindowAttributes(h, 0, 1, LWA_ALPHA);
        // Chrome opens new windows maximised if the last one was, and a maximised window ignores its size:
        // restore it (without taking focus) so it shrinks to a small corner instead of a full-screen layer
        ShowWindow(h, 4);
        SetWindowPos(h, topmost, 0, 0, 480, 800, NOACTIVATE);
        if (restore != IntPtr.Zero && restore != h) {
            // a tap of Alt is what Windows accepts as permission to move the focus back
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            SetForegroundWindow(restore);
        }
    }
}
"@

$win = $null
$list = $null
$playButton = $null
$lastSearch = [DateTime]::MinValue
# the hidden lyrics window this script opened itself, and when it last tried
$ownWindow = [LyricsWindow]::FindHidden()
$lastOpen = [DateTime]::MinValue
$lastToggle = [DateTime]::MinValue

function ChromePath {
    try {
        $p = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe' -ErrorAction Stop).'(default)'
        if ($p -and (Test-Path $p)) { return $p }
    } catch {}
    foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if (-not $root) { continue }
        $p = Join-Path $root 'Google\Chrome\Application\chrome.exe'
        if (Test-Path $p) { return $p }
    }
    return $null
}

# Nobody has the lyrics open: open a Spotify window of our own on its lyrics page in the user's (already
# signed-in) Chrome and make it invisible at once. The user's own windows are never touched. Kept open all
# the time: if it disappears it is back within a few seconds.
function OpenOwnWindow {
    if (([DateTime]::Now - $script:lastOpen).TotalSeconds -lt 8) { return }
    $script:lastOpen = [DateTime]::Now
    $hidden = [LyricsWindow]::FindHidden()
    if ($hidden -ne [IntPtr]::Zero) { $script:ownWindow = $hidden; return }
    $chrome = ChromePath
    if (-not $chrome) { return }
    $before = [LyricsWindow]::Chrome()
    $focus = [LyricsWindow]::GetForegroundWindow()
    Start-Process -FilePath $chrome -ArgumentList '--new-window', 'https://open.spotify.com/lyrics'
    for ($i = 0; $i -lt 200; $i++) {
        Start-Sleep -Milliseconds 25
        $new = @([LyricsWindow]::Chrome() | Where-Object { $before -notcontains $_ })
        if ($new.Count -gt 0) {
            $script:ownWindow = $new[0]
            [LyricsWindow]::Hide($new[0], $focus)
            # Chrome places a new window again once its page starts loading; hide it once more after that
            Start-Sleep -Milliseconds 1500
            [LyricsWindow]::Hide($new[0], [IntPtr]::Zero)
            return
        }
    }
}

# The lyrics view (the microphone button) in OUR window only - switched on if the page came up without it.
# Already on with no lines in it means Spotify has no lyrics for this song ("you'll have to guess").
$noText = $false
function ShowLyricsInOwnWindow {
    if (([DateTime]::Now - $script:lastToggle).TotalSeconds -lt 5) { return }
    $script:lastToggle = [DateTime]::Now
    $w = $AE::FromHandle($script:ownWindow)
    foreach ($b in $w.FindAll($Scope::Descendants, $buttonCond)) {
        if ($b.Current.Name -notmatch '^(Tekst|Lyrics)$') { continue }
        $p = $null
        if ($b.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$p)) {
            $on = $p.Current.ToggleState -eq [System.Windows.Automation.ToggleState]::On
            $script:noText = $on
            if (-not $on) { $p.Toggle() }
        }
        return
    }
}

# The track playing, as the player bar's "Now playing" link: its album page URL and its label. Read about once
# a second; the album URL is what the client turns into the exact cover.
$nowLink = $null
$lastNow = [DateTime]::MinValue
$times = @()
$barButton = $null
$hyperlinkCond = New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Hyperlink)
function NowPlaying {
    if (([DateTime]::Now - $script:lastNow).TotalMilliseconds -lt 250) { return $null }
    $script:lastNow = [DateTime]::Now
    $w = $script:win
    if ($null -eq $w -and $script:ownWindow -ne [IntPtr]::Zero -and [LyricsWindow]::IsWindow($script:ownWindow)) {
        $w = $AE::FromHandle($script:ownWindow)
    }
    if ($null -eq $w) { return $null }
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        if ($null -eq $script:nowLink) {
            $script:nowLink = $w.FindAll($Scope::Descendants, $hyperlinkCond) |
                Where-Object { $_.Current.Name -match '^(Teraz odtwarzane|Now playing)' } | Select-Object -First 1
            if ($null -eq $script:nowLink) { return $null }
            # the player bar's two times: elapsed and length, the only m:ss texts on either side of the slider
            $script:times = @($w.FindAll($Scope::Descendants, $textCond) |
                Where-Object { $_.Current.Name -match '^\d+:\d\d(:\d\d)?$' } | Select-Object -First 2)
            $script:barButton = FindPlayButton $w
        }
        try {
            $v = $null
            if (-not $script:nowLink.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$v)) { return $null }
            $pos = if ($script:times.Count -ge 1) { $script:times[0].Current.Name } else { '' }
            $len = if ($script:times.Count -ge 2) { $script:times[1].Current.Name } else { '' }
            $play = '1'
            if ($null -ne $script:barButton) { $play = if ($script:barButton.Current.Name -match '^(Wstrzymaj|Pauza|Pause)$') { '1' } else { '0' } }
            return "now`t" + $v.Current.Value + "`t" + (Clean $script:nowLink.Current.Name) + "`t$pos`t$len`t$play"
        } catch { $script:nowLink = $null }
    }
    return $null
}

function Clean($s) { if ($null -eq $s) { return '' } return ($s -replace "[`t`r`n]", ' ').Trim() }

# Every Spotify web player window: "Spotify ..." when idle, "Track - Artist" with a bullet while playing.
function SpotifyWindows {
    foreach ($w in $AE::RootElement.FindAll($Scope::Children, $All)) {
        if ($w.Current.ClassName -ne 'Chrome_WidgetWin_1') { continue }
        $n = $w.Current.Name
        if ($n -match 'Spotify' -or $n.Contains($bullet)) { $w }
    }
}

# The script never touches Spotify's own view. It looks through every Spotify window for one that already
# shows the lyrics - typically a second window kept behind the game, so the one in use stays as it is.
function FindLyricsWindow {
    $script:win = $null
    $script:noTextSeen = $false
    # Our own hidden window is the one to read; the user's windows (large, slow to scan) only when there is none.
    $candidates = @()
    if ($script:ownWindow -ne [IntPtr]::Zero -and [LyricsWindow]::IsWindow($script:ownWindow)) {
        $candidates = @($AE::FromHandle($script:ownWindow))
    } else {
        $candidates = @(SpotifyWindows)
    }
    foreach ($w in $candidates) {
        if ($null -eq $script:win) { $script:win = $w }
        $l = FindList $w
        # a song without lyrics still has other lists on its page (videos, the artist's bio): never take one
        # of those for the lyrics
        if ($script:noTextSeen) { return $null }
        if ($null -ne $l) { $script:win = $w; return $l }
    }
    return $null
}

# Spotify's own "no lyrics for this song" notes, in Polish and English: "I tu nas masz. Ciagle staramy sie zdobyc
# tekst tej piosenki", "Tekst musisz wyspiewac z pamieci", "You'll have to guess the lyrics for this one", ...
$noTextRx = 'staramy si. zdoby. tekst|z pami.ci|guess the lyrics|still working on|no lyrics|nie ma tekstu'

# The lyrics list: of all containers holding rows of text, the one with the most rows.
function FindList($w) {
    $groups = @{}
    foreach ($t in $w.FindAll($Scope::Descendants, $textCond)) {
        if ($t.Current.Name -match $script:noTextRx) { $script:noTextSeen = $true }
        $row = $walker.GetParent($walker.GetParent($t))
        if ($null -eq $row -or [string]::IsNullOrEmpty($row.Current.ClassName)) { continue }
        $container = $walker.GetParent($row)
        if ($null -eq $container) { continue }
        $key = [string]::Join(',', $container.GetRuntimeId())
        # an object, not a hashtable: a hashtable's .Count is its number of keys, not this tally
        if (-not $groups.ContainsKey($key)) { $groups[$key] = [pscustomobject]@{ Element = $container; Rows = 0 } }
        $groups[$key].Rows++
    }
    $best = $groups.Values | Sort-Object Rows -Descending | Select-Object -First 1
    if ($best -and $best.Rows -ge 6) { return $best.Element }
    return $null
}

# The player bar's play/pause button: of the visible buttons named exactly Play/Pause, the lowest (list
# rows have play buttons of their own higher up).
function FindPlayButton($w) {
    $best = $null
    foreach ($b in $w.FindAll($Scope::Descendants, $buttonCond)) {
        # 'Pauza' is what the Polish player calls it while playing; off-screen ones belong to track rows
        if ($b.Current.Name -notmatch '^(Odtw.rz|Wstrzymaj|Pauza|Play|Pause)$' -or $b.Current.IsOffscreen) { continue }
        if ($null -eq $best -or $b.Current.BoundingRectangle.Y -gt $best.Current.BoundingRectangle.Y) { $best = $b }
    }
    return $best
}

function Playing {
    if ($null -eq $script:playButton) { return '1' }
    try { if ($script:playButton.Current.Name -match '^(Wstrzymaj|Pauza|Pause)$') { return '1' } else { return '0' } }
    catch { $script:playButton = $null; return '1' }
}

# Line texts never change while a song plays; read each once per lyrics view and keep it.
$rowTexts = @{}
function RowText($row) {
    $key = [string]::Join(",", $row.GetRuntimeId())
    if ($script:rowTexts.ContainsKey($key)) { return $script:rowTexts[$key] }
    $text = RowTextNow $row
    $script:rowTexts[$key] = $text
    return $text
}

function RowTextNow($row) {
    $t = $row.FindFirst($Scope::Descendants, $textCond)
    if ($null -eq $t) { return '' }
    return Clean $t.Current.Name
}

while ($true) {
    $out = 'nowindow'
    try {
        if ($null -eq $list) {
            if (([DateTime]::Now - $lastSearch).TotalMilliseconds -ge 1000) {
                $lastSearch = [DateTime]::Now
                $list = FindLyricsWindow
                if ($null -ne $list) { $playButton = FindPlayButton $win; $noText = $false; $nowLink = $null; $rowTexts = @{} }
                elseif ($ownWindow -ne [IntPtr]::Zero -and [LyricsWindow]::IsWindow($ownWindow)) { ShowLyricsInOwnWindow }
                else { OpenOwnWindow }
            }
            if ($null -eq $win) { $out = 'nowindow' }
            elseif ($null -eq $list) { $out = if ($noText -or $noTextSeen) { 'notext' } else { 'nolyrics' } }
        }
        if ($null -ne $list) {
            $classCache.Push()
            try { $rows = @($list.FindAll($Scope::Children, $All)) }
            finally { $classCache.Pop() }

            # Lines share a base class; anything else in the list (a spacer, the credits) has its own base.
            # Plain loops on purpose: this runs ~20 times a second and PowerShell pipelines are far too slow.
            $parts = New-Object 'System.Collections.Generic.List[string[]]'
            $bases = @{}
            foreach ($r in $rows) {
                $p = $r.Cached.ClassName.Split(' ')
                $parts.Add($p)
                $bases[$p[0]] = 1 + [int]$bases[$p[0]]
            }
            $base = $null; $most = 0
            foreach ($k in $bases.Keys) { if ($bases[$k] -gt $most) { $most = $bases[$k]; $base = $k } }
            $kept = New-Object 'System.Collections.Generic.List[object]'
            $states = New-Object 'System.Collections.Generic.List[string]'
            for ($i = 0; $i -lt $rows.Count; $i++) {
                if ($parts[$i][0] -ne $base) { continue }
                $kept.Add($rows[$i])
                # the state is the class right after the base; extra classes (a hidden spacer) do not count
                $states.Add($(if ($parts[$i].Length -gt 1) { $parts[$i][1] } else { '' }))
            }
            $rows = $kept
            if ($rows.Count -lt 6) { throw 'lyrics view gone' }

            $counts = @{}
            foreach ($s in $states) { $counts[$s] = 1 + [int]$counts[$s] }
            $active = -1
            for ($i = 0; $i -lt $states.Count; $i++) { if ($counts[$states[$i]] -eq 1) { $active = $i; break } }

            $play = Playing
            if ($active -ge 0) {
                $next = if ($active + 1 -lt $rows.Count) { RowText $rows[$active + 1] } else { '' }
                $out = "line`t$active`t" + (RowText $rows[$active]) + "`t$next`t$play"
            } elseif ($counts.Count -eq 1) {
                $out = "intro`t-1`t`t" + (RowText $rows[0]) + "`t$play"
            } else {
                $out = "line`t-1`t`t`t$play"
            }
        }
    } catch {
        # the page re-rendered (new song, view closed): look again
        $list = $null
        $nowLink = $null
        $out = if ($null -eq $win) { 'nowindow' } elseif ($noTextSeen) { 'notext' } else { 'nolyrics' }
        # look again right away, not a second later: a re-rendered list is usually back on the next pass
        $lastSearch = [DateTime]::MinValue
    }
    try { $now = NowPlaying; if ($now) { [Console]::Out.WriteLine($now) } } catch { $nowLink = $null }
    [Console]::Out.WriteLine($out)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 50
}
