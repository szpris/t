# NasiTracker 完整部署说明

## 架构概览

```
Android App  ──POST /api/upload──►  Python Server (track.nasi.cn:55000)
                                     └── SQLite3 tracker.db
                                     └── Web 查询页面 http://track.nasi.cn:55000/
```

---

## 一、服务端部署（Debian 12）

### 1. 上传文件到服务器

```bash
scp -r tracker-server/ user@track.nasi.cn:/opt/tracker
```

### 2. 在服务器上执行

```bash
cd /opt/tracker

# 安装依赖
apt install python3 python3-venv python3-pip -y

# 创建虚拟环境
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# ★★★ 先修改 server.py 中的密码和密钥 ★★★
# WEB_PASSWORD = "NasiAdmin2026!"  ← 改成你自己的
# API_KEY       = "NasiTracker2026SecretKey#X9mZ"  ← 与App保持一致

# 测试运行
python3 server.py
```

### 3. 设为系统服务（开机自启）

```bash
cp tracker.service /etc/systemd/system/tracker.service
systemctl daemon-reload
systemctl enable tracker
systemctl start tracker
systemctl status tracker
```

### 4. 防火墙开放端口

```bash
ufw allow 55000/tcp
```

### 5. 验证服务

```bash
curl -X POST http://track.nasi.cn:55000/api/upload \
  -H "Content-Type: application/json" \
  -d '{"api_key":"NasiTracker2026SecretKey#X9mZ","latitude":23.12,"longitude":113.23,"accuracy":10,"timestamp":1711000000000}'
# 预期返回: {"ok":true}
```

---

## 二、Android App 编译（使用在线编译服务）

### 方法一：Replit（推荐，免费）

1. 打开 https://replit.com，注册/登录
2. 新建 Repl → 选 **Bash** 类型
3. 将 `tracker-app/` 文件夹全部内容打包上传
4. 在 Shell 中执行：

```bash
# 安装JDK 17
apt-get install -y openjdk-17-jdk

# 给gradlew添加执行权限
chmod +x gradlew

# 构建release APK
./gradlew assembleDebug
```

5. 产物在 `app/build/outputs/apk/debug/app-debug.apk`，下载到本地

### 方法二：GitHub Actions（推荐，稳定）

1. 将 `tracker-app/` 上传到 GitHub 仓库（可设私有）
2. 在仓库中创建 `.github/workflows/build.yml`：

```yaml
name: Build APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build APK
        run: |
          chmod +x gradlew
          ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

3. Push 代码后，在 Actions 页面等待构建完成，下载 Artifact

### 方法三：Codemagic（Android专用CI）

1. 注册 https://codemagic.io
2. 连接 GitHub 仓库，选择 Android 项目
3. 配置 Gradle 命令 `assembleDebug`，自动触发构建并下载 APK

---

## 三、App 安装

1. 将 APK 传输到 Android 手机（USB/微信/邮件）
2. 在手机设置中开启"允许安装未知来源应用"
3. 点击 APK 文件安装
4. 首次运行时授权：位置权限（精确）、通知权限
5. 如需后台持续运行，在手机电池优化设置中将 NasiTracker 设为"不限制"

---

## 四、重要配置对应关系

| 配置项 | Android App（Constants.kt） | 服务端（server.py） |
|--------|--------------------------|-------------------|
| API 密钥 | `API_KEY` | `API_KEY` |
| 服务器地址 | `SERVER_URL` | - |
| Web 登录密码 | - | `WEB_PASSWORD` |

**修改 API_KEY 后，App 和服务端必须同步修改，否则无法上传！**

---

## 五、Web 界面使用

| 功能 | URL |
|------|-----|
| 登录 | http://track.nasi.cn:55000/login |
| 查询数据 | http://track.nasi.cn:55000/ |
| 导出 CSV | http://track.nasi.cn:55000/export?fmt=csv |
| 导出高德地图 | http://track.nasi.cn:55000/export?fmt=gaode |
| 导出百度地图 | http://track.nasi.cn:55000/export?fmt=baidu |
| 导出谷歌地图 | http://track.nasi.cn:55000/export?fmt=google |
| 按时间筛选 | ?start=2026-03-01&end=2026-03-31 |

> **地图导出说明：** 导出的 HTML 文件需要将 `YOUR_GAODE_KEY` / `YOUR_BAIDU_KEY` / `YOUR_GOOGLE_KEY` 替换为对应平台的地图 API Key 方可显示地图。

---

## 六、App 使用说明

| 功能 | 操作 |
|------|------|
| 启动追踪 | 点"▶ 启动追踪"按钮 |
| 停止追踪 | 点"■ 停止追踪"按钮 |
| 刷新记录 | 点"↻ 刷新"按钮 |
| 进入调试界面 | 连续快速点击状态栏文字 5 次 |
| 调试上传当前位置 | 调试界面 → "测试：上传当前位置" |
| 手动上传坐标 | 调试界面 → 输入纬度/经度 → 点"上传" |

---

## 七、安全提示

- 修改 `API_KEY` 为随机长字符串（建议32位以上）
- 修改 `WEB_PASSWORD` 为强密码
- 建议在服务器上配置 nginx 反向代理 + HTTPS
- 数据库文件 `tracker.db` 定期备份
