# Distributed Banking System

SC6103 Distributed Systems - Course Project for 2025-2026 Trimester 2

## 项目概述 (Overview)

本项目实现了一个基于UDP协议的分布式银行系统，包含：
- **Java服务器** (`server_java/`) - 处理所有银行业务逻辑
- **Java客户端** (`client_java/`) - 交互式命令行客户端
- **C++客户端** (`client_cpp/`) - 交互式命令行客户端

This project implements a distributed banking system using UDP protocol, consisting of:
- **Java Server** (`server_java/`) - Handles all banking business logic
- **Java Client** (`client_java/`) - Interactive command-line client
- **C++ Client** (`client_cpp/`) - Interactive command-line client

## 功能特性 (Features)

### 银行服务 (Banking Services)
1. **开户 (Open Account)** - 创建新账户，指定姓名、密码、货币类型和初始余额
2. **关户 (Close Account)** - 关闭现有账户
3. **存款 (Deposit)** - 向账户存入指定金额（非幂等操作）
4. **取款 (Withdraw)** - 从账户取出指定金额（非幂等操作）
5. **查询余额 (Query Balance)** - 查询账户余额（幂等操作，额外功能）
6. **转账 (Transfer)** - 在两个账户间转账（非幂等操作，额外功能）
7. **监控注册 (Monitor Register)** - 注册接收账户更新回调通知

### 系统特性 (System Features)
- **UDP通信** - 使用UDP套接字进行客户端-服务器通信
- **自定义协议** - 完全自实现的marshalling/unmarshalling
- **调用语义** - 支持at-least-once和at-most-once两种调用语义
- **消息丢失模拟** - 服务器可配置请求/响应丢失概率用于测试
- **请求去重** - at-most-once模式下自动过滤重复请求
- **回调机制** - 支持多客户端同时监控账户更新

## 同态加密隐私投票演示 (Homomorphic Voting Demo)

新增一个独立的密码学演示：使用 **Paillier 加密** 实现加法同态的隐私投票统计。

- **传统计票**：逐个解密选票后再求和，计票人能看到每一票。
- **同态计票**：对密文直接求和（密文相乘），最后只解密一次得到总票数。

### 运行演示 (Run the Demo)
```bash
cd server_java

# Windows (CMD/PowerShell)
javac -source 8 -target 8 -d out src\Paillier.java src\HomomorphicVotingDemo.java
java -cp out HomomorphicVotingDemo

# Linux/macOS
javac -source 8 -target 8 -d out src/Paillier.java src/HomomorphicVotingDemo.java
java -cp out HomomorphicVotingDemo
```

## 协议设计 (Protocol Design)

### 消息头格式 (Header Format - 24 bytes)
| 字段 | 类型 | 大小 | 描述 |
|------|------|------|------|
| magic | uint32 | 4 | 魔数 0x42414E4B ("BANK") |
| version | uint8 | 1 | 协议版本 (1) |
| msgType | uint8 | 1 | 消息类型 (1=Request, 2=Reply, 3=Callback) |
| opCode | uint16 | 2 | 操作码 |
| flags | uint16 | 2 | 标志位 (bit0: at-most-once) |
| status | uint16 | 2 | 状态码 |
| requestId | uint64 | 8 | 请求ID |
| bodyLen | uint32 | 4 | 消息体长度 |

### 操作码 (Operation Codes)
| 码值 | 操作 | 幂等性 |
|------|------|--------|
| 1 | OPEN | 非幂等 |
| 2 | CLOSE | 非幂等 |
| 3 | DEPOSIT | 非幂等 |
| 4 | WITHDRAW | 非幂等 |
| 5 | MONITOR_REGISTER | - |
| 6 | QUERY_BALANCE | 幂等 |
| 7 | TRANSFER | 非幂等 |
| 100 | CALLBACK_UPDATE | - |

### 状态码 (Status Codes)
| 码值 | 状态 | 描述 |
|------|------|------|
| 0 | OK | 成功 |
| 1 | ERR_BAD_REQUEST | 请求格式错误 |
| 2 | ERR_AUTH | 认证失败 |
| 3 | ERR_NOT_FOUND | 账户不存在 |
| 4 | ERR_CURRENCY | 货币类型不匹配 |
| 5 | ERR_INSUFFICIENT_FUNDS | 余额不足 |
| 6 | ERR_PASSWORD_FORMAT | 密码格式错误 |

## 编译和运行 (Build and Run)

### 环境要求 (Requirements)
- **Java**: JDK 8 或更高版本
- **C++**: MinGW-w64 或 Visual Studio (支持C++17)

### 编译 (Compilation)

#### 编译服务器 (Compile Server)
```bash
cd server_java
compile.bat
```

#### 编译Java客户端 (Compile Java Client)
```bash
cd client_java
compile.bat
```

#### 编译C++客户端 (Compile C++ Client)
```bash
cd client_cpp
compile.bat
```

### 运行 (Running)

#### 启动服务器 (Start Server)
```bash
cd server_java
run.bat --port 9000

# 启用消息丢失模拟（用于测试）
run.bat --port 9000 --lossReq 0.3 --lossRep 0.3
```

#### 启动Java客户端 (Start Java Client)
```bash
cd client_java
run.bat --server 127.0.0.1 --port 9000 --sem atmost

# 使用at-least-once语义
run.bat --server 127.0.0.1 --port 9000 --sem atleast
```

#### 启动C++客户端 (Start C++ Client)
```bash
cd client_cpp
run.bat --server 127.0.0.1 --port 9000 --sem atmost
```

### 命令行参数 (Command Line Arguments)

#### 服务器参数 (Server Arguments)
| 参数 | 默认值 | 描述 |
|------|--------|------|
| --port | 9000 | 服务器端口 |
| --lossReq | 0.0 | 请求丢失概率 (0.0-1.0) |
| --lossRep | 0.0 | 响应丢失概率 (0.0-1.0) |

#### 客户端参数 (Client Arguments)
| 参数 | 默认值 | 描述 |
|------|--------|------|
| --server | 127.0.0.1 | 服务器IP地址 |
| --port | 9000 | 服务器端口 |
| --sem | atmost | 调用语义 (atmost/atleast) |
| --timeout | 500 | 超时时间(毫秒) |
| --retry | 5 | 重试次数 |

## 调用语义对比 (Invocation Semantics Comparison)

### At-Least-Once
- **实现**: 客户端超时重传，服务器不过滤重复请求
- **适用**: 幂等操作（如查询余额）
- **问题**: 非幂等操作可能重复执行（如存款可能多次执行）

### At-Most-Once
- **实现**: 
  - 每个请求带唯一ID
  - 服务器维护已处理请求的历史记录
  - 重复请求返回缓存的响应
- **适用**: 所有操作，尤其是非幂等操作
- **优点**: 保证每个请求最多执行一次

### 实验验证 (Experiment)
使用 `--lossReq 0.3 --lossRep 0.3` 启动服务器，观察：
1. At-least-once语义下，存款操作可能被重复执行
2. At-most-once语义下，即使重传也只执行一次

## 演示步骤 (Demo Steps)

### 基本功能演示
1. 启动服务器: `cd server_java && run.bat`
2. 启动客户端1 (Java): `cd client_java && run.bat`
3. 启动客户端2 (C++): `cd client_cpp && run.bat`

### 回调功能演示
1. 客户端A选择 "7) MONITOR register"，设置监控时间
2. 客户端B执行存款/取款/开户等操作
3. 观察客户端A收到的回调通知

### 语义对比演示
1. 启动服务器并启用消息丢失: `run.bat --lossReq 0.3 --lossRep 0.3`
2. 使用at-least-once语义进行存款，观察可能的重复执行
3. 使用at-most-once语义进行存款，验证只执行一次

## 项目结构 (Project Structure)

```
Distributed_Bank/
├── server_java/           # Java服务器
│   ├── src/
│   │   ├── Protocol.java  # 协议定义
│   │   ├── Bank.java      # 银行业务逻辑
│   │   └── Server.java    # UDP服务器主程序
│   ├── compile.bat        # 编译脚本
│   └── run.bat            # 运行脚本
│
├── client_java/           # Java客户端
│   ├── src/
│   │   ├── Protocol.java  # 协议定义
│   │   ├── Cli.java       # 命令行界面
│   │   └── Main.java      # 客户端主程序
│   ├── compile.bat        # 编译脚本
│   └── run.bat            # 运行脚本
│
├── client_cpp/            # C++客户端
│   ├── src/
│   │   ├── protocol.hpp   # 协议定义
│   │   ├── protocol.cpp   # 协议实现
│   │   ├── client.hpp     # 客户端头文件
│   │   ├── client.cpp     # 客户端实现
│   │   └── main.cpp       # 主程序入口
│   ├── compile.bat        # 编译脚本
│   └── run.bat            # 运行脚本
│
└── README.md              # 本文档
```

## 注意事项 (Notes)

1. **跨语言兼容**: Java和C++客户端使用相同的协议，可互操作
2. **字节序**: 所有多字节数据使用大端序（网络字节序）
3. **密码存储**: 密码在协议中固定为16字节，不足部分填充0
4. **账户持久化**: 当前版本仅在内存中存储账户数据

## 作者 (Authors)

SC6103 Distributed Systems Course Project Team
