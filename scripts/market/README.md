# 新浪历史行情适配器

Java `SinaMarketDataProvider` → 受限本地 Python 进程 → 新浪 HTTPS 入口 → 固定 AKShare 解码器。依赖仍是 [G02 的固定 AKShare 1.19.1](../probes/requirements-sina.txt) 与 Node 18+；不导入整个库，不执行远程 JavaScript。共享固定版本、算法校验和、禁显式代理、禁重定向及 8 秒单次 HTTP 逻辑。只接受 AAPL、GLD、SLV 和 2026 年内最多 120 个日历日。

后端工作目录为 `backend/`；默认关闭外部取数进程。运维设置（不是前端参数）：

```properties
INVESTLENS_MARKET_SINA_ENABLED=true
INVESTLENS_MARKET_SINA_PYTHON=C:/path/to/python.exe
INVESTLENS_MARKET_SINA_NODE=node
INVESTLENS_MARKET_SINA_SCRIPT=../scripts/market/sina_adapter.py
```

Python 环境安装 `python -m pip install --no-deps -r scripts/probes/requirements-sina.txt`。仅本地开发临时目录安装时，设置 `PYTHONPATH` 指向该安装目录；正式部署应使用固定虚拟环境。以上开关只允许启动适配器，不能覆盖源资格和许可门槛。错误映射包括超时、限流、空结果、解码依赖不可用与上游拒绝；无自动重试。Java 一次只允许一个进程，20 秒总等待上限，stdout 限 256 KiB，stderr 丢弃，超时/结束清理子进程。

单次诊断（只打印元数据、不打印价格）：

```powershell
'{"schemaVersion":"1.0.0","symbol":"GLD","start":"2026-06-25","end":"2026-10-22"}' | python -B scripts/market/sina_adapter.py --summary-only
```

Java 跨进程在线验收需显式执行，不进入常规离线测试：

```powershell
# 在 backend/ 执行，market.python 指向装有固定 AKShare 的解释器
.\mvnw.cmd -o -B -ntp '-Dtest=SinaOnlineProbe' '-Dmarket.python=C:\path\to\python.exe' test
```

输出只有 `backend/target/g09-sina-summary.json` 的元数据摘要。禁止保存标准输出的原始行情模式到长期文件。真实大陆网络、使用条件与复权仍须独立核验；当前程序没有把技术候选加入生产白名单。
