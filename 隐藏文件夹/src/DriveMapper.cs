using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace HiddenDisk
{
    /// <summary>
    /// 盘符选择（现有最大盘符+1 的空闲字母）、盘符映射/卸载、残留映射清理。
    /// 映射通道：优先 DefineDosDevice（等价 subst，免管理员权限）；
    /// 部分安全软件（如卡巴斯基）会拦截未签名进程调用该 API（Win32 错误码 5），
    /// 此时回退到微软签名的 subst.exe 子进程；成功与否一律以盘符状态核实，不信任 API 返回值。
    /// </summary>
    public static class DriveMapper
    {
        private const uint DDD_REMOVE_DEFINITION = 0x2;
        private const uint DDD_EXACT_MATCH_ON_REMOVE = 0x4;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool DefineDosDevice(uint dwFlags, string lpDeviceName, string lpTargetPath);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint QueryDosDevice(string lpDeviceName, StringBuilder lpTargetPath, int ucchMax);

        /// <summary>取「现有最大盘符的下一个空闲字母」（C~F → G）。无可用字母返回 null。</summary>
        public static string PickFreeLetter()
        {
            bool[] used = new bool[26];
            foreach (string drive in Environment.GetLogicalDrives())
            {
                string d = drive.Trim().TrimEnd('\\').TrimEnd(':');
                if (d.Length == 1) used[char.ToUpperInvariant(d[0]) - 'A'] = true;
            }
            int max = -1;
            for (int i = 25; i >= 2; i--)
            {
                if (used[i]) { max = i; break; }
            }
            if (max < 0) max = 2;
            for (int i = max + 1; i <= 25; i++)
            {
                if (!used[i]) return ((char)('A' + i)) + ":";
            }
            return null;
        }

        /// <summary>
        /// 把 letter 映射到 targetPath。DefineDosDevice 被安全软件拦截时回退 subst.exe；
        /// 以「盘符真的可访问」为成功判据。
        /// </summary>
        public static bool Map(string letter, string targetPath)
        {
            DefineDosDevice(0, letter, targetPath);
            if (WaitLetterReady(letter, 600)) return true;

            RunSubst(letter + " \"" + targetPath + "\"");
            return WaitLetterReady(letter, 600);
        }

        /// <summary>卸载 letter 上指向 targetPath 的映射；以 QueryDosDevice 查不到为准。</summary>
        public static bool Unmap(string letter, string targetPath)
        {
            DefineDosDevice(DDD_REMOVE_DEFINITION | DDD_EXACT_MATCH_ON_REMOVE, letter, targetPath);
            if (WaitLetterGone(letter, 600)) return true;

            RunSubst(letter + " /d");
            return WaitLetterGone(letter, 600);
        }

        private static bool WaitLetterReady(string letter, int totalMs)
        {
            for (int waited = 0; ; waited += 150)
            {
                if (Directory.Exists(letter + "\\")) return true;
                if (waited >= totalMs) return false;
                Thread.Sleep(150);
            }
        }

        private static bool WaitLetterGone(string letter, int totalMs)
        {
            for (int waited = 0; ; waited += 150)
            {
                if (QueryTarget(letter) == null) return true;
                if (waited >= totalMs) return false;
                Thread.Sleep(150);
            }
        }

        /// <summary>
        /// 经微软签名的 subst.exe 执行映射/卸载（隐藏窗口、等待完成）。
        /// 参数全部由程序内部生成（盘符字母与 exe 同目录的数据路径），不含任何用户输入。
        /// </summary>
        private static void RunSubst(string substArguments)
        {
            try
            {
                string cmd = "\"" + Path.Combine(Environment.SystemDirectory, "subst.exe") + "\" " + substArguments;
                object shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
                Type t = shell.GetType();
                object[] runArgs = new object[] { cmd, 0, true };
                t.InvokeMember("Run", BindingFlags.InvokeMethod, null, shell, runArgs);
            }
            catch
            {
                // 回退通道不可用（如 WSH 被禁用）——由调用方的状态核实兜底
            }
        }

        /// <summary>读取 letter 的设备目标；无映射或读取失败返回 null。真实卷返回 "\Device\..." 开头。</summary>
        public static string QueryTarget(string letter)
        {
            StringBuilder sb = new StringBuilder(1024);
            uint n = QueryDosDevice(letter, sb, sb.Capacity);
            if (n == 0) return null;
            string s = sb.ToString();
            int zero = s.IndexOf('\0');
            if (zero >= 0) s = s.Substring(0, zero);
            s = s.TrimEnd('\\');
            if (s.StartsWith("\\??\\", StringComparison.OrdinalIgnoreCase)) s = s.Substring(4);
            return s;
        }

        /// <summary>
        /// 清理指向本实例数据文件夹的残留映射，返回卸载数量。启动/退出各调用一次。
        /// 只按完整路径精确匹配：其他盘的数据文件夹（其他实例正在使用的盘符）一律不动，互不干扰；
        /// 真实卷（\Device\）也一律不动。
        /// </summary>
        public static int CleanupResiduals(string vaultDir)
        {
            int removed = 0;
            string expected = Normalize(vaultDir);
            if (expected.Length == 0) return 0;
            for (int i = 0; i < 26; i++)
            {
                string letter = ((char)('A' + i)) + ":";
                string target = QueryTarget(letter);
                if (target == null) continue;
                if (target.StartsWith("\\Device\\", StringComparison.OrdinalIgnoreCase)) continue;
                if (!string.Equals(Normalize(target), expected, StringComparison.OrdinalIgnoreCase)) continue;
                if (Unmap(letter, expected)) removed++;
            }
            return removed;
        }

        /// <summary>letter 当前是否恰好映射到 expected（用于映射后的归属校验，防止盘符竞争）。</summary>
        public static bool TargetMatches(string letter, string expected)
        {
            string target = QueryTarget(letter);
            return target != null
                && string.Equals(Normalize(target), Normalize(expected), StringComparison.OrdinalIgnoreCase);
        }

        private static string Normalize(string path)
        {
            string p = path == null ? "" : path.Trim();
            while (p.EndsWith("\\")) p = p.Substring(0, p.Length - 1);
            return p;
        }
    }
}
