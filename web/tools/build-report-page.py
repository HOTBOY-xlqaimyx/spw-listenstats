#!/usr/bin/env python3
"""把报告页做成可部署版本：为给定 URL 生成内联二维码 SVG，注入 `<!--QR-->` 位置。

- 二维码由 python-qrcode 生成（部署期一次性生成，页面本身仍无外部依赖）
- 幂等：重复执行会先移除上一次注入的 `<!--QR-START-->…<!--QR-END-->` 块
- 离线版（插件内导出的自包含报告）用不含二维码的源文件即可

用法: python3 build-report-page.py <源 index.html> <输出路径> <报告页 URL>
"""
import re
import sys

import qrcode

src, out, url = sys.argv[1], sys.argv[2], sys.argv[3]
html = open(src, encoding="utf-8").read()

# 清掉上一次注入的二维码（幂等）
html = re.sub(r"<!--QR-START-->.*?<!--QR-END-->", "<!--QR-->", html, flags=re.S)
if "<!--QR-->" not in html:
    sys.exit("✗ 找不到 <!--QR--> 占位符")

# border=4 → 矩阵自带 4 模块静默区（扫码必需）
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=1, border=4)
qr.add_data(url)
qr.make(fit=True)
m = qr.get_matrix()
n = len(m)

parts = []
for y, row in enumerate(m):
    x = 0
    while x < n:
        if row[x]:
            run = 1
            while x + run < n and row[x + run]:
                run += 1
            parts.append("M%d %dh%dv1h-%dz" % (x, y, run, run))
            x += run
        else:
            x += 1

svg = (
    '<!--QR-START--><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" '
    'shape-rendering="crispEdges" role="img" aria-label="扫码打开 {url}">'
    '<rect width="{n}" height="{n}" fill="#ffffff"/>'
    '<path d="{d}" fill="#000000"/></svg><!--QR-END-->'
).format(n=n, url=url, d="".join(parts))

html = html.replace("<!--QR-->", svg, 1)
open(out, "w", encoding="utf-8").write(html)
print("二维码：版本 %d，%d×%d 模块（含静默区），编码 %s → %s" % (qr.version, n, n, url, out))
