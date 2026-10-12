# RULES.md 5장(난수)의 참조 구현. fixtures/rng/basic.json 의 기대값을 계산한다.
# Windows PowerShell 5.1 에서 부호 없는 64비트 연산을 하기 위해 BigInteger 를 쓴다.
# 실행: powershell -ExecutionPolicy Bypass -File tools\rng_reference.ps1 [-Seeds 0,12345]
param([long[]]$Seeds = @(0, 12345))

Add-Type -AssemblyName System.Numerics
$BI = [System.Numerics.BigInteger]
$M = $BI::Pow(2, 64)
function Hex([string]$h) { $BI::Parse("0" + $h, 'AllowHexSpecifier') }
$GOLDEN = Hex '9E3779B97F4A7C15'
$C1 = Hex 'BF58476D1CE4E5B9'
$C2 = Hex '94D049BB133111EB'

class SplitMix { [System.Numerics.BigInteger]$s }

function Shr($z, $n) { $BI::Divide($z, $BI::Pow(2, $n)) }

function Next([SplitMix]$st) {
    $st.s = ($st.s + $GOLDEN) % $M
    $z = $st.s
    $z = ($BI::op_ExclusiveOr($z, (Shr $z 30)) * $C1) % $M
    $z = ($BI::op_ExclusiveOr($z, (Shr $z 27)) * $C2) % $M
    $BI::op_ExclusiveOr($z, (Shr $z 31))
}

function NextInt([SplitMix]$st, [int]$n) { [int]((Next $st) % $n) }

# RULES 5.2: root = SplitMix64(G), 스트림 k 의 초기 state = root 의 k 번째 출력
function New-Stream($g, [int]$k) {
    $root = [SplitMix]::new(); $root.s = $BI::new($g)
    $v = $null
    for ($i = 1; $i -le $k; $i++) { $v = Next $root }
    $st = [SplitMix]::new(); $st.s = $v
    $st
}

$raw = [SplitMix]::new(); $raw.s = $BI::Zero
"splitmix state=0: " + ((1..3 | ForEach-Object { (Next $raw).ToString() }) -join ', ')

foreach ($g in $Seeds) {
    $s1 = New-Stream $g 1
    "seed=$g stream1 first: " + ((1..3 | ForEach-Object { (Next $s1).ToString() }) -join ', ')

    # RULES 5.3: 7-bag
    $bag = New-Stream $g 1
    $bags = @()
    for ($b = 0; $b -lt 3; $b++) {
        $a = @('I', 'J', 'L', 'O', 'S', 'T', 'Z')
        for ($i = 6; $i -ge 1; $i--) {
            $j = NextInt $bag ($i + 1)
            $tmp = $a[$i]; $a[$i] = $a[$j]; $a[$j] = $tmp
        }
        $bags += ($a -join '')
    }
    "seed=$g bags: " + ($bags -join ' ')

    # RULES 5.4: 가비지 구멍 (스트림 2 = P1, 3 = P2)
    foreach ($k in 2, 3) {
        $gs = New-Stream $g $k
        $h = NextInt $gs 10
        $holes = @($h)
        for ($i = 0; $i -lt 5; $i++) { $h = ($h + 1 + (NextInt $gs 9)) % 10; $holes += $h }
        "seed=$g stream$k holes: " + ($holes -join ', ')
    }

    # RULES 13: S-mix 줄 수 (스트림 4)
    $sc = New-Stream $g 4
    "seed=$g S-mix lines: " + ((1..6 | ForEach-Object { 2 + (NextInt $sc 3) }) -join ', ')
}
