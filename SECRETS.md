# 墨阅 MoRead — 密钥与配置总表

> ⚠️ 此文件包含所有敏感信息，仅存在于私有仓库（siqiongbil/booksapp）。
> 切勿推到公开仓库或随 APK 分发。

---

## 一、Android 签名证书

| 项 | 值 |
|---|---|
| 证书文件 | `keystore/moread-release.jks` |
| 别名 (alias) | `moread` |
| 密码 (store/key 通用) | `UvKuDLdGtIzLoWbmyfOc` |
| 有效期 | 30 年（至 ~2055 年） |
| 构建配置 | `keystore.properties`（工程根目录） |

丢失后果：无法再对已发布的 app 发布更新（Android 签名机制）。

---

## 二、GitHub 账号与令牌

### siqiongbil 账号（代码仓库 + 应用更新）

| 令牌 | 权限 | 用途 | 状态 |
|---|---|---|---|
| `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` | booksapp 读写（管理） | 推代码、建 Release、管理仓库 | ✅ 有效 |
| `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` | booksapp 只读 | **内置在 app 中**用于更新检测 | ✅ 有效 |

### books-start 账号（书库仓库）

| 令牌 | 权限 | 用途 | 状态 |
|---|---|---|---|
| `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` | books-start/books 读写 | 上传书籍、管理书库 | ✅ 有效 |
| `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` | books-start/books 只读 | app 内配置读书库 | ✅ 有效 |

---

## 三、仓库地址

| 仓库 | 地址 | 用途 |
|---|---|---|
| siqiongbil/booksapp | https://github.com/siqiongbil/booksapp | 应用源码 + Release（更新） |
| books-start/books | https://github.com/books-start/books | 书库（693 文件 952MB） |

---

## 四、App 内使用

### 配置书库（手机上）
```
书架 → 设置 → 仓库设置
  默认仓库: https://github.com/books-start/books
  PAT:      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
```

### 推送代码（电脑上）
```bash
cd F:\epub
git push booksapp main
# 或走 Data API（git 通道被墙时）
```

### 构建正式包
```bash
cd F:\epub
set JAVA_HOME=E:\Program Files\Android\Android Studio\jbr
gradlew.bat :app:assembleRelease
# 输出: app\build\outputs\apk\release\app-release.apk
```

---

## 五、安全提醒

- 所有令牌都应定期轮换（GitHub → Settings → Developer settings → Fine-grained tokens）
- 此文件与 keystore 已加入 .gitignore 的例外（会推到私有仓库 booksapp）
- 如仓库意外转公开，立即吊销所有令牌并更换 keystore 密码
