# OPPO 电视优化 / tvOS 桌面

针对 OPPO 智能电视 **OPPOCNM632**（Android 9，2GB / 8GB）的去广告、后台精简与 tvOS 风格桌面。
基于 [tv-vibehack](https://github.com/Gathin23/tv-vibehack)，不修改系统分区，全部可逆。

## 使用

```bash
./connect-tv.sh
./disable-tv.sh debloat.txt
./build-install.sh
./restore.sh
```

配置：`launcher/app/src/main/res/values/config.xml`
依赖：macOS、Python 3.9+、JDK 17、Android SDK 34
测试：`python3 -m unittest discover -s tests`

MIT License，基于 tv-vibehack。
