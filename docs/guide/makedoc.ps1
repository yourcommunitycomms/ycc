# Builds the getting-started guide as a .docx (plain OOXML, zipped), with screenshots.
param([string]$out)
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$shots = Join-Path $PSScriptRoot "screenshots"
$work = Join-Path $env:TEMP "ycc-docx"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force "$work\word\media", "$work\word\_rels", "$work\_rels", "$work\docProps" | Out-Null

function Esc([string]$s) { return [System.Security.SecurityElement]::Escape($s) }

$script:imgs = @()
# Shrink a screenshot to $w pixels wide (keeps the document small) and register it.
function AddImage([string]$name, [int]$w) {
    $src = [System.Drawing.Image]::FromFile("$shots\$name.png")
    $h = [int]($src.Height * $w / $src.Width)
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($src, 0, 0, $w, $h)
    $g.Dispose(); $src.Dispose()
    $i = $script:imgs.Count + 1
    $bmp.Save("$work\word\media\image$i.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $o = [pscustomobject]@{ Rid = "rIdImg$i"; File = "image$i.png"; W = $w; H = $h }
    $script:imgs += $o
    return $o
}

# Inline picture XML, $widthIn inches wide.
function Pic($img, [double]$widthIn) {
    $cx = [long]($widthIn * 914400)
    $cy = [long]($cx * $img.H / $img.W)
    $script:picId++
    $id = $script:picId
    $rid = $img.Rid
    return "<w:r><w:drawing><wp:inline distT=`"0`" distB=`"0`" distL=`"0`" distR=`"0`"><wp:extent cx=`"$cx`" cy=`"$cy`"/>" +
        "<wp:docPr id=`"$id`" name=`"Picture $id`"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=`"1`"/></wp:cNvGraphicFramePr>" +
        "<a:graphic><a:graphicData uri=`"http://schemas.openxmlformats.org/drawingml/2006/picture`"><pic:pic><pic:nvPicPr><pic:cNvPr id=`"0`" name=`"$($img.File)`"/><pic:cNvPicPr/></pic:nvPicPr>" +
        "<pic:blipFill><a:blip r:embed=`"$rid`"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
        "<pic:spPr><a:xfrm><a:off x=`"0`" y=`"0`"/><a:ext cx=`"$cx`" cy=`"$cy`"/></a:xfrm><a:prstGeom prst=`"rect`"><a:avLst/></a:prstGeom><a:ln w=`"6350`"><a:solidFill><a:srgbClr val=`"9E9E9E`"/></a:solidFill></a:ln></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
}

# Text with **bold** pieces.
function Runs([string]$t) {
    $o = ""
    $parts = $t -split '\*\*'
    for ($i = 0; $i -lt $parts.Count; $i++) {
        if ($parts[$i].Length -eq 0) { continue }
        $b = if ($i % 2 -eq 1) { "<w:rPr><w:b/></w:rPr>" } else { "" }
        $o += "<w:r>$b<w:t xml:space=`"preserve`">$(Esc $parts[$i])</w:t></w:r>"
    }
    return $o
}

function P([string]$t, [string]$style = "") {
    $ps = if ($style) { "<w:pPr><w:pStyle w:val=`"$style`"/></w:pPr>" } else { "" }
    return "<w:p>$ps$(Runs $t)</w:p>"
}

$body = New-Object System.Text.StringBuilder
function Add([string]$x) { [void]$script:body.Append($x) }

# One step: text paragraphs on the left, screenshots on the right.
function Step([string[]]$text, [object[]]$pics, [double]$picIn = 1.9) {
    $left = ($text | ForEach-Object { P $_ }) -join ""
    $right = ""
    if ($pics.Count -ge 2) {
        # Two screenshots side by side, smaller.
        $both = ""
        foreach ($p in $pics) { $both += (Pic $p 1.3) + "<w:r><w:t xml:space=`"preserve`"> </w:t></w:r>" }
        $right = "<w:p><w:pPr><w:jc w:val=`"center`"/></w:pPr>$both</w:p>"
    } else {
        foreach ($p in $pics) { $right += "<w:p><w:pPr><w:spacing w:after=`"80`"/><w:jc w:val=`"center`"/></w:pPr>$(Pic $p $picIn)</w:p>" }
    }
    if (-not $right) { $right = "<w:p/>" }
    Add ("<w:tbl><w:tblPr><w:tblW w:w=`"9638`" w:type=`"dxa`"/>" +
        "<w:tblBorders><w:top w:val=`"single`" w:sz=`"4`" w:color=`"CFE3D4`"/><w:bottom w:val=`"single`" w:sz=`"4`" w:color=`"CFE3D4`"/></w:tblBorders><w:tblLayout w:type=`"fixed`"/>" +
        "<w:tblCellMar><w:top w:w=`"100`" w:type=`"dxa`"/><w:left w:w=`"100`" w:type=`"dxa`"/><w:bottom w:w=`"100`" w:type=`"dxa`"/><w:right w:w=`"100`" w:type=`"dxa`"/></w:tblCellMar></w:tblPr>" +
        "<w:tblGrid><w:gridCol w:w=`"5638`"/><w:gridCol w:w=`"4000`"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr>" +
        "<w:tc><w:tcPr><w:tcW w:w=`"5638`" w:type=`"dxa`"/></w:tcPr>$left</w:tc>" +
        "<w:tc><w:tcPr><w:tcW w:w=`"4000`" w:type=`"dxa`"/><w:vAlign w:val=`"center`"/></w:tcPr>$right</w:tc></w:tr></w:tbl><w:p><w:pPr><w:spacing w:after=`"0`"/></w:pPr></w:p>")
}

function Note([string]$t) {
    Add ("<w:p><w:pPr><w:pBdr><w:left w:val=`"single`" w:sz=`"24`" w:space=`"8`" w:color=`"F0C75E`"/></w:pBdr><w:shd w:val=`"clear`" w:color=`"auto`" w:fill=`"FFF8E1`"/><w:ind w:left=`"200`"/></w:pPr>$(Runs $t)</w:p>")
}

. "$PSScriptRoot\guide-content.ps1"

$docXml = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?>" +
    "<w:document xmlns:w=`"http://schemas.openxmlformats.org/wordprocessingml/2006/main`" xmlns:r=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships`" " +
    "xmlns:wp=`"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing`" xmlns:a=`"http://schemas.openxmlformats.org/drawingml/2006/main`" " +
    "xmlns:pic=`"http://schemas.openxmlformats.org/drawingml/2006/picture`"><w:body>" + $body.ToString() +
    "<w:sectPr><w:footerReference w:type=`"default`" r:id=`"rIdFooter`"/><w:pgSz w:w=`"11906`" w:h=`"16838`"/><w:pgMar w:top=`"1134`" w:right=`"1134`" w:bottom=`"1134`" w:left=`"1134`" w:header=`"567`" w:footer=`"567`" w:gutter=`"0`"/></w:sectPr></w:body></w:document>"

$styles = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-AU"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3D2B"/><w:sz w:val="44"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:color w:val="5A7A63"/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:pBdr><w:bottom w:val="single" w:sz="8" w:space="2" w:color="F0C75E"/></w:pBdr><w:spacing w:before="320" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3D2B"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2E5A40"/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:rPr><w:color w:val="808080"/><w:sz w:val="18"/></w:rPr></w:style>
</w:styles>
"@

$footer = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?><w:ftr xmlns:w=`"http://schemas.openxmlformats.org/wordprocessingml/2006/main`"><w:p><w:pPr><w:pStyle w:val=`"Footer`"/><w:jc w:val=`"center`"/></w:pPr><w:r><w:t xml:space=`"preserve`">Your Community Communications · Getting started · page </w:t></w:r><w:r><w:fldChar w:fldCharType=`"begin`"/></w:r><w:r><w:instrText xml:space=`"preserve`"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=`"separate`"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=`"end`"/></w:r></w:p></w:ftr>"

$rels = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?><Relationships xmlns=`"http://schemas.openxmlformats.org/package/2006/relationships`">" +
    "<Relationship Id=`"rIdStyles`" Type=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles`" Target=`"styles.xml`"/>" +
    "<Relationship Id=`"rIdFooter`" Type=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer`" Target=`"footer1.xml`"/>"
foreach ($im in $script:imgs) { $rels += "<Relationship Id=`"$($im.Rid)`" Type=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image`" Target=`"media/$($im.File)`"/>" }
$rels += "</Relationships>"

$ct = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?><Types xmlns=`"http://schemas.openxmlformats.org/package/2006/content-types`">" +
    "<Default Extension=`"rels`" ContentType=`"application/vnd.openxmlformats-package.relationships+xml`"/><Default Extension=`"xml`" ContentType=`"application/xml`"/>" +
    "<Default Extension=`"png`" ContentType=`"image/png`"/>" +
    "<Override PartName=`"/word/document.xml`" ContentType=`"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml`"/>" +
    "<Override PartName=`"/word/styles.xml`" ContentType=`"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml`"/>" +
    "<Override PartName=`"/word/footer1.xml`" ContentType=`"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml`"/>" +
    "<Override PartName=`"/docProps/core.xml`" ContentType=`"application/vnd.openxmlformats-package.core-properties+xml`"/></Types>"

$rootRels = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?><Relationships xmlns=`"http://schemas.openxmlformats.org/package/2006/relationships`">" +
    "<Relationship Id=`"rId1`" Type=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument`" Target=`"word/document.xml`"/>" +
    "<Relationship Id=`"rId2`" Type=`"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties`" Target=`"docProps/core.xml`"/></Relationships>"

$core = "<?xml version=`"1.0`" encoding=`"UTF-8`" standalone=`"yes`"?><cp:coreProperties xmlns:cp=`"http://schemas.openxmlformats.org/package/2006/metadata/core-properties`" xmlns:dc=`"http://purl.org/dc/elements/1.1/`" xmlns:dcterms=`"http://purl.org/dc/terms/`" xmlns:xsi=`"http://www.w3.org/2001/XMLSchema-instance`">" +
    "<dc:title>Your Community Communications: getting started</dc:title><dc:creator>Your Community Communications</dc:creator></cp:coreProperties>"

$enc = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText("$work\word\document.xml", $docXml, $enc)
[IO.File]::WriteAllText("$work\word\styles.xml", $styles, $enc)
[IO.File]::WriteAllText("$work\word\footer1.xml", $footer, $enc)
[IO.File]::WriteAllText("$work\word\_rels\document.xml.rels", $rels, $enc)
[IO.File]::WriteAllText("$work\[Content_Types].xml", $ct, $enc)
[IO.File]::WriteAllText("$work\_rels\.rels", $rootRels, $enc)
[IO.File]::WriteAllText("$work\docProps\core.xml", $core, $enc)

if (Test-Path $out) { Remove-Item $out }
$zip = [System.IO.Compression.ZipFile]::Open($out, 'Create')
# [Content_Types].xml first, as Word expects.
$order = @("$work\[Content_Types].xml") + (Get-ChildItem $work -Recurse -File | Where-Object { $_.Name -ne '[Content_Types].xml' } | ForEach-Object { $_.FullName })
foreach ($f in $order) {
    $name = $f.Substring($work.Length + 1).Replace('\', '/')
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f, $name)
}
$zip.Dispose()
"wrote $out ($((Get-Item $out).Length) bytes, $($script:imgs.Count) images)"
