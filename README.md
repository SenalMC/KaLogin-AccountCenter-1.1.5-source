# KaLogin-AccountCenter

Modern Account Security Center Extension for KaLogin
适用于 Paper 1.21.8+ Dialog API 登录服

━━━━━━━━━━━━━━━━━━━━

## 简介

KaLogin-AccountCenter（简称 KAC）是一款基于 KaLogin 生态开发的现代化账号安全中心扩展插件。

插件专为高版本登录服务器设计，提供：

* 邮箱绑定
* 邮箱验证码
* 找回密码
* 修改密码
* Dialog API 账号安全中心
* KaMenu 玩家协议联动
* CatSeedLogin 数据库兼容
* KaLogin 原生数据库兼容

KAC 使用 Minecraft 1.21.7+ 原生 Dialog API 构建现代化交互界面，无需传统箱子 GUI、铁砧输入或聊天栏输入密码。

━━━━━━━━━━━━━━━━━━━━

## 功能特性

* Dialog API 原生界面
* 现代化账号安全中心
* 邮箱绑定 / 更换邮箱
* 邮箱验证码
* 找回密码
* 修改密码
* 未登录状态下找回密码
* KaLogin UI Hook
* KaMenu 玩家协议联动
* CatSeedLogin 数据库兼容
* KaLogin 原生数据库兼容
* 自定义页面配置
* 自定义关闭后动作
* SMTP 邮件发送支持

━━━━━━━━━━━━━━━━━━━━

## 支持版本

* Paper 1.21.8+
* Java 21+

━━━━━━━━━━━━━━━━━━━━

## 安装方法

将以下插件放入：

```text
/plugins/
```

* KaLogin
* KaLogin-AccountCenter
* （可选）KaMenu

启动服务器后插件会自动生成：

```text
plugins/KaLogin-AccountCenter/
```

━━━━━━━━━━━━━━━━━━━━

## 数据库兼容

### CatSeedLogin

```yml
storage:
  mode: catseed

catseed:
  sqlite-file: 'plugins/CatSeedLogin/accounts.db'
```

### KaLogin

```yml
storage:
  mode: kalogin

kalogin:
  sqlite-file: 'plugins/KaLogin/data.db'
```

━━━━━━━━━━━━━━━━━━━━

## SMTP 配置示例

```yml
mail:
  enabled: true

  smtp-host: 'smtp.qiye.aliyun.com'
  smtp-port: 465

  ssl: true
  starttls: false

  username: 'example@example.com'
  password: 'SMTP授权码'

  from-address: 'example@example.com'
  from-name: 'KaLogin Account Center'
```

━━━━━━━━━━━━━━━━━━━━

## KaLogin 登录界面接入

在 KaLogin 登录 UI 中增加：

```yml
forgot_password:
  type: 'message'
  text: |
    <gray>忘记密码？点击 </gray><text=<aqua><bold>[ 找回账号 ]</bold>;hover=<gray>通过绑定邮箱找回密码;command=/kac recover>
```

━━━━━━━━━━━━━━━━━━━━

## KaMenu 玩家协议接入

```yml
<text=<aqua><bold>[ 打开账号安全中心 ]</bold>;hover=<gray>绑定邮箱 / 修改密码 / 安全设置;command=/kac center>
```

━━━━━━━━━━━━━━━━━━━━

## 指令

### 玩家

```text
/kac
/kac center
/kac bind
/kac recover
/kac change
```

### 管理员

```text
/kac reload
```

━━━━━━━━━━━━━━━━━━━━

## 推荐登录流程

```text
KaLogin 登录
→ 玩家协议
→ KAC 账号安全中心（可选）
→ 主大厅
```

━━━━━━━━━━━━━━━━━━━━

## 开源说明

This project is based on the KaLogin ecosystem.

Thanks to KaLogin and KaMenu for providing the modern Dialog API framework.

━━━━━━━━━━━━━━━━━━━━

## 作者

SenalMC / 云墨工艺
