using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace HiddenDisk
{
    internal static class Program
    {
        // 互斥体/事件按数据文件夹路径哈希命名：
        // 同一个数据文件夹的第二个实例 = 通知首实例退出并卸盘（toggle 语义）；
        // 不同盘的数据文件夹互不相干，可同时运行、各占一个盘符（G、H、I…自动递增）。
        private static Mutex _mutex;
        private static EventWaitHandle _exitEvent;
        private static volatile bool _exitSignaled;
        private static volatile Form _currentForm;

        [STAThread]
        private static int Main(string[] args)
        {
            foreach (string a in args)
            {
                if (a == "--selftest") return SelfTest.Run();
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            // C 盘（系统盘）不创建数据文件夹：直接提示使用数据盘副本
            if (VaultService.VaultDirectory == null)
            {
                MessageBox.Show("C 盘不创建数据文件夹。\r\n请把本程序复制到 D、E 等数据盘的任意文件夹后，从那里运行——\r\n" +
                    "它将只管理所在数据盘的文件（各数据盘的副本可同时运行、互不干扰）。", "隐藏盘");
                return 1;
            }

            string suffix = InstanceSuffix(VaultService.VaultDirectory);
            bool createdNew;
            _mutex = new Mutex(true, "Local\\HiddenDisk.Mutex." + suffix, out createdNew);
            if (!createdNew)
            {
                SignalRunningInstance("Local\\HiddenDisk.Exit." + suffix);
                return 0;
            }

            _exitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\HiddenDisk.Exit." + suffix);
            Thread watcher = new Thread(WatchExitEvent);
            watcher.IsBackground = true;
            watcher.Start();

            try
            {
                return RunApp();
            }
            finally
            {
                try { DriveMapper.CleanupResiduals(VaultService.VaultDirectory); } catch { }
                try { _mutex.ReleaseMutex(); } catch { }
            }
        }

        private static string InstanceSuffix(string vaultDir)
        {
            string key = (vaultDir == null ? "" : vaultDir).TrimEnd('\\').ToLowerInvariant();
            byte[] digest;
            using (SHA256 sha = SHA256.Create())
            {
                digest = sha.ComputeHash(Encoding.UTF8.GetBytes(key));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.Append(digest[i].ToString("x2"));
            return sb.ToString();
        }

        /// <summary>第二实例：发退出信号，等首实例释放互斥（≤3 秒）后自身退出。</summary>
        private static void SignalRunningInstance(string eventName)
        {
            EventWaitHandle ev = null;
            if (EventWaitHandle.TryOpenExisting(eventName, out ev))
            {
                try { ev.Set(); } catch { }
                try { ev.Dispose(); } catch { }
            }
            try
            {
                if (_mutex.WaitOne(TimeSpan.FromSeconds(3))) _mutex.ReleaseMutex();
            }
            catch (AbandonedMutexException)
            {
                // 首实例异常退出留下的废弃互斥体，直接退出即可
            }
        }

        private static void WatchExitEvent()
        {
            try { _exitEvent.WaitOne(); }
            catch { return; }
            _exitSignaled = true;
            Form f = _currentForm;
            if (f != null && f.IsHandleCreated)
            {
                try { f.BeginInvoke((MethodInvoker)delegate { f.Close(); }); return; }
                catch { }
            }
            // 窗体尚未就绪（如启动早期/报错弹窗阶段）：直接结束进程，保证 toggle 语义始终成立
            Environment.Exit(0);
        }

        private static int RunApp()
        {
            string vaultDir = VaultService.VaultDirectory;

            // 旧版一次性迁移：exe 同目录的数据文件夹 → 本盘根目录（仅迁移文件数据，密码不继承）
            try { VaultService.MigrateLegacyVault(VaultService.LegacyVaultDirectory, vaultDir); } catch { }

            try { VaultService.EnsureVault(); }
            catch (Exception ex)
            {
                MessageBox.Show("无法创建数据文件夹：" + ex.Message + "\r\n路径：" + vaultDir, "隐藏盘");
                return 1;
            }
            try { DriveMapper.CleanupResiduals(vaultDir); } catch { }

            string cfgPath = Config.PathForVault(vaultDir);
            Config cfg = Config.Load(cfgPath);
            if (cfg == null)
            {
                SetupForm setup = new SetupForm(cfgPath);
                _currentForm = setup;
                Application.Run(setup);
                if (_exitSignaled || setup.DialogResult != DialogResult.OK) return 0;
                cfg = setup.CreatedConfig;   // 刚设完密码，本次直接进入，不再重复询问
            }
            else
            {
                PasswordForm pwd = new PasswordForm(cfg);
                _currentForm = pwd;
                Application.Run(pwd);
                if (_exitSignaled || pwd.DialogResult != DialogResult.OK) return 0;
            }

            string letter = DriveMapper.PickFreeLetter();
            if (letter == null)
            {
                MessageBox.Show("没有可用盘符（C~Z 已占满）。", "隐藏盘");
                return 1;
            }
            if (!DriveMapper.Map(letter, vaultDir) || !DriveMapper.TargetMatches(letter, vaultDir))
            {
                try { DriveMapper.Unmap(letter, vaultDir); } catch { }
                MessageBox.Show("盘符映射失败。\r\n常见原因：安全软件（如卡巴斯基）拦截了未签名程序的盘符映射。\r\n" +
                    "处理：首次弹出拦截提示时选择「允许」，或在本程序加入安全软件信任区后重试，\r\n也可以右键「以管理员身份运行」。", "隐藏盘");
                return 1;
            }

            VaultForm vault = new VaultForm(letter, vaultDir);
            _currentForm = vault;
            Application.Run(vault);
            return 0;
        }
    }

    /// <summary>--selftest 需要往启动它的控制台输出结果；winexe 默认无控制台，用 AttachConsole 挂回父进程。</summary>
    internal static class NativeConsole
    {
        public const uint ATTACH_PARENT_PROCESS = 0xFFFFFFFF;

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool AttachConsole(uint dwProcessId);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool FreeConsole();
    }

    /// <summary>
    /// --selftest 自检（无 UI）：密码哈希 / 每数据文件夹独立配置 / 锁定持久化 / 盘符选择 /
    /// 盘符映射-读写-清理链路 / 移入隐藏（含跨盘拒绝、重名与护栏）/ 旧数据文件夹迁移。
    /// 结果同时打印控制台并写入 exe 目录下 selftest.log，ExitCode 0=全过 1=有失败。
    /// </summary>
    internal static class SelfTest
    {
        private static int _failCount;
        private static string _logPath;

        public static int Run()
        {
            try { NativeConsole.AttachConsole(NativeConsole.ATTACH_PARENT_PROCESS); } catch { }
            _logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "selftest.log");
            try { File.Delete(_logPath); } catch { }

            Log("== HiddenDisk --selftest ==");

            string tempRoot = Path.GetTempPath();
            string cfgDir = Path.Combine(tempRoot, "hdst_cfg_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            string mapDir = Path.Combine(tempRoot, "hdst_map_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            string vaultDir = Path.Combine(tempRoot, "hdst_vault_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            string migOld = Path.Combine(tempRoot, "hdst_migold_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            string migNew = Path.Combine(tempRoot, "hdst_mignew_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            try
            {
                string cfgFile = TestConfigAndCrypto(cfgDir);
                TestLock(cfgFile);
                TestDriveLetterAndMap(mapDir);
                TestVaultMove(vaultDir);
                TestMigration(migOld, migNew);
            }
            catch (Exception ex)
            {
                Log("[FAIL] 自检异常：" + ex.ToString());
                _failCount++;
            }
            finally
            {
                CleanupDir(cfgDir);
                CleanupDir(mapDir);
                CleanupDir(vaultDir);
                CleanupDir(migOld);
                CleanupDir(migNew);
            }

            Log(_failCount == 0 ? "== 全部通过 ==" : "== 失败 " + _failCount.ToString() + " 项 ==");
            try { NativeConsole.FreeConsole(); } catch { }
            return _failCount == 0 ? 0 : 1;
        }

        private static void Log(string line)
        {
            Console.WriteLine(line);
            if (_logPath == null) return;
            try { File.AppendAllText(_logPath, line + "\r\n", Encoding.UTF8); } catch { }
        }

        private static void Check(bool condition, string name)
        {
            Log((condition ? "[PASS] " : "[FAIL] ") + name);
            if (!condition) _failCount++;
        }

        private static string TestConfigAndCrypto(string dir)
        {
            Directory.CreateDirectory(dir);
            string f = Path.Combine(dir, "unit.cfg");

            Config cfg = Config.Create("abc123", f);
            Check(cfg.Hash != null && cfg.Hash.Length == 32, "PBKDF2-SHA256 输出 32 字节");
            Check(cfg.Salt != null && cfg.Salt.Length == 16, "随机盐 16 字节");
            Check(cfg.Verify("abc123"), "正确密码校验通过");
            Check(!cfg.Verify("abc124"), "错误密码校验拒绝");
            Check(Pbkdf2Sha256.FixedTimeEquals(
                Pbkdf2Sha256.Derive("abc123", cfg.Salt, cfg.Iterations, 32), cfg.Hash), "同参数推导结果一致");
            Check(Config.PathForVault(@"C:\HiddenDiskVault") != Config.PathForVault(@"E:\HiddenDiskVault"),
                "不同盘的数据文件夹使用相互独立的配置文件");

            cfg.Save();
            Config loaded = Config.Load(f);
            Check(loaded != null && loaded.Verify("abc123"), "配置保存/读取往返一致");

            return f;
        }

        private static void TestLock(string cfgFile)
        {
            Config cfg = Config.Load(cfgFile);
            if (cfg == null) { cfg = Config.Create("abc123", cfgFile); cfg.Save(); }

            for (int i = 0; i < 5; i++) cfg.RegisterFailure();
            Check(cfg.IsLocked, "连错 5 次后进入锁定");
            Check(cfg.LockRemaining <= Config.LockDuration && cfg.LockRemaining > TimeSpan.FromMinutes(14.9),
                "锁定剩余时间约 15 分钟");

            Config reloaded = Config.Load(cfgFile);
            Check(reloaded != null && reloaded.IsLocked, "锁定状态已持久化（重启不可绕过）");

            reloaded.LockUntilUtcTicks = DateTime.UtcNow.Ticks - 1;   // 模拟锁定期满
            reloaded.Save();
            Config expired = Config.Load(cfgFile);
            Check(expired != null && !expired.IsLocked && expired.FailedAttempts == 0,
                "锁定期满自动解除且试错计数归零");
        }

        private static void TestDriveLetterAndMap(string mapDir)
        {
            string letter = DriveMapper.PickFreeLetter();
            Check(letter != null && letter.Length == 2 && letter[1] == ':', "盘符选择返回形如 G: 的空闲字母");
            if (letter == null) return;

            bool occupied = false;
            foreach (string d in Environment.GetLogicalDrives())
            {
                if (string.Equals(d.TrimEnd('\\'), letter, StringComparison.OrdinalIgnoreCase)) occupied = true;
            }
            Check(!occupied, "所选盘符未被现有逻辑盘占用");

            Directory.CreateDirectory(mapDir);
            File.WriteAllText(Path.Combine(mapDir, "probe.txt"), "x");

            bool mapped = DriveMapper.Map(letter, mapDir);
            Check(mapped, "盘符映射成功（API/subst 通道）");
            if (!mapped)
            {
                Log("[提示] DefineDosDevice 被拦截（Win32 错误码 " + Marshal.GetLastWin32Error().ToString() +
                    "）且 subst 回退未生效：请将本程序加入安全软件（如卡巴斯基）信任区，或以管理员身份运行。");
                return;
            }

            bool visible = Directory.Exists(letter + "\\");
            Check(visible, "映射后盘符立即可见");
            if (visible)
            {
                Check(File.ReadAllText(letter + "\\probe.txt") == "x", "经盘符读到的内容与目标一致");
            }
            Check(DriveMapper.TargetMatches(letter, mapDir), "映射归属校验一致");

            int removed = DriveMapper.CleanupResiduals(mapDir);
            Check(removed >= 1 && DriveMapper.QueryTarget(letter) == null, "残留清理卸载本实例映射");
        }

        private static void TestVaultMove(string vaultDir)
        {
            string srcDir = vaultDir + "_src";
            try
            {
                Directory.CreateDirectory(vaultDir);
                VaultService.SetDirectoryForTests(vaultDir);
                VaultService.EnsureVault();
                FileAttributes attr = File.GetAttributes(vaultDir);
                Check((attr & FileAttributes.Hidden) != 0 && (attr & FileAttributes.System) != 0,
                    "数据文件夹带 Hidden+System 属性");

                Directory.CreateDirectory(srcDir);
                string file1 = Path.Combine(srcDir, "a.txt");
                File.WriteAllText(file1, "1");
                string sub = Path.Combine(srcDir, "sub");
                Directory.CreateDirectory(sub);
                File.WriteAllText(Path.Combine(sub, "b.txt"), "2");
                string file2 = Path.Combine(srcDir, "dup.txt");
                File.WriteAllText(file2, "3");
                File.WriteAllText(Path.Combine(vaultDir, "dup.txt"), "old");   // 占位，验证重名加序号

                List<string> errors = new List<string>();
                int ok = VaultService.MoveIntoVault(new string[] { file1, sub, file2, vaultDir }, errors);
                Check(ok == 3 && errors.Count == 1, "3 项移入成功，隐藏盘自身拒绝移入");
                Check(!File.Exists(file1) && !Directory.Exists(sub) && !File.Exists(file2), "移入后原位置消失");
                Check(File.Exists(Path.Combine(vaultDir, "a.txt")) && File.Exists(Path.Combine(vaultDir, "sub\\b.txt")),
                    "移入后内容完整");
                Check(File.Exists(Path.Combine(vaultDir, "dup (2).txt")), "重名自动加序号");
                Check(File.ReadAllText(Path.Combine(vaultDir, "dup (2).txt")) == "3", "重名文件内容正确");

                errors = new List<string>();
                int ok2 = VaultService.MoveIntoVault(new string[] { Path.Combine(vaultDir, "a.txt") }, errors);
                Check(ok2 == 0 && errors.Count == 0, "已在隐藏盘内的项目再次拖入为无操作");

                // 跨盘拒绝：构造一个与数据文件夹不同卷根的路径（本实例只管理本盘）
                string vaultRoot = Path.GetPathRoot(Path.GetFullPath(vaultDir));
                string otherRoot = (vaultRoot != null && vaultRoot[0] == 'C') ? "Q:\\" : "C:\\";
                errors = new List<string>();
                int ok3 = VaultService.MoveIntoVault(new string[] { otherRoot + "__hd_no_such\\x.txt" }, errors);
                Check(ok3 == 0 && errors.Count == 1, "跨盘文件被拒绝（本实例只管理本盘数据）");
            }
            finally
            {
                try { if (Directory.Exists(srcDir)) Directory.Delete(srcDir, true); } catch { }
            }
        }

        private static void TestMigration(string migOld, string migNew)
        {
            Directory.CreateDirectory(migOld);
            File.WriteAllText(Path.Combine(migOld, "keep.txt"), "v");
            bool moved = VaultService.MigrateLegacyVault(migOld, migNew);
            Check(moved && File.Exists(Path.Combine(migNew, "keep.txt")) && !Directory.Exists(migOld),
                "旧版数据文件夹一次性迁移到新位置");
            Check(!VaultService.MigrateLegacyVault(migOld, migNew), "迁移幂等（重复调用不再动作）");
        }

        private static void CleanupDir(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return;
                File.SetAttributes(dir, FileAttributes.Directory);
                Directory.Delete(dir, true);
            }
            catch { }
        }
    }
}
