# Reads the Windows media session (System Media Transport Controls) - the same source as the volume
# overlay - and prints one tab-separated line every 300 ms:
#   app  title  artist  positionMs  endMs  status  lastUpdatedUnixMs
# Spotify is preferred; otherwise whichever session is playing; "none" when there is nothing.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

function Clean($s) { if ($null -eq $s) { return '' } return ($s -replace "[`t`r`n]", ' ') }

while ($true) {
    try {
        $sessions = @($mgr.GetSessions())
        $pick = $sessions | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1
        if (-not $pick) { $pick = $sessions | Where-Object { $_.GetPlaybackInfo().PlaybackStatus -eq 'Playing' } | Select-Object -First 1 }
        if ($pick) {
            $p = Await ($pick.TryGetMediaPropertiesAsync()) $propsType
            $tl = $pick.GetTimelineProperties()
            $pb = $pick.GetPlaybackInfo()
            [Console]::Out.WriteLine(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f (Clean $pick.SourceAppUserModelId), (Clean $p.Title), (Clean $p.Artist),
                [long]$tl.Position.TotalMilliseconds, [long]$tl.EndTime.TotalMilliseconds, $pb.PlaybackStatus,
                $tl.LastUpdatedTime.ToUnixTimeMilliseconds()))
        } else {
            [Console]::Out.WriteLine('none')
        }
    } catch {
        [Console]::Out.WriteLine('none')
    }
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 300
}
