using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace HiddenDisk
{
    /// <summary>
    /// 每个数据文件夹一份独立配置（密码哈希 + 试错/锁定状态），
    /// 存放在 %LOCALAPPDATA%\HiddenDisk\vault_{路径哈希}.cfg —— 不同盘的数据文件夹互不干扰，
    /// 同一个数据文件夹无论 exe 在盘内哪个位置启动，用的都是同一份配置。
    /// </summary>
    public class Config
    {
        public byte[] Salt;             // 哈希盐
        public byte[] Hash;             // PBKDF2 结果
        public int Iterations;          // 迭代次数
        public int FailedAttempts;      // 当前连续失败次数（0~MaxAttempts）
        public long LockUntilUtcTicks;  // 锁定截止时间（UTC ticks，0=未锁定）

        public const int MaxAttempts = 5;
        public static readonly TimeSpan LockDuration = TimeSpan.FromMinutes(15);
        public const int DefaultIterations = 50000;

        private readonly string _filePath;

        public Config(string filePath)
        {
            _filePath = filePath;
        }

        public string FilePath
        {
            get { return _filePath; }
        }

        private static string AppDataDir
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HiddenDisk");
            }
        }

        /// <summary>数据文件夹 → 独立配置文件路径（按规范化完整路径哈希，8 位十六进制）。</summary>
        public static string PathForVault(string vaultDir)
        {
            string key = (vaultDir == null ? "" : vaultDir).TrimEnd('\\').ToLowerInvariant();
            byte[] digest;
            using (SHA256 sha = SHA256.Create())
            {
                digest = sha.ComputeHash(Encoding.UTF8.GetBytes(key));
            }
            StringBuilder sb = new StringBuilder("vault_");
            for (int i = 0; i < 4; i++) sb.Append(digest[i].ToString("x2"));
            sb.Append(".cfg");
            return Path.Combine(AppDataDir, sb.ToString());
        }

        /// <summary>读取配置；文件不存在或损坏返回 null（视为未配置，进入首次设置）。</summary>
        public static Config Load(string filePath)
        {
            try
            {
                if (!File.Exists(filePath)) return null;
                Config c = new Config(filePath);
                string[] lines = File.ReadAllLines(filePath, Encoding.UTF8);
                foreach (string raw in lines)
                {
                    string line = raw.Trim();
                    if (line.Length == 0) continue;
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    string key = line.Substring(0, eq).Trim();
                    string val = line.Substring(eq + 1).Trim();
                    if (key == "salt") c.Salt = Convert.FromBase64String(val);
                    else if (key == "hash") c.Hash = Convert.FromBase64String(val);
                    else if (key == "iter") c.Iterations = int.Parse(val);
                    else if (key == "failed") c.FailedAttempts = int.Parse(val);
                    else if (key == "lock") c.LockUntilUtcTicks = long.Parse(val);
                }
                if (c.Salt == null || c.Hash == null || c.Iterations <= 0) return null;
                return c;
            }
            catch
            {
                return null;
            }
        }

        public void Save()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_filePath));
            StringBuilder sb = new StringBuilder();
            sb.Append("salt=").Append(Convert.ToBase64String(Salt)).Append("\r\n");
            sb.Append("hash=").Append(Convert.ToBase64String(Hash)).Append("\r\n");
            sb.Append("iter=").Append(Iterations.ToString()).Append("\r\n");
            sb.Append("failed=").Append(FailedAttempts.ToString()).Append("\r\n");
            sb.Append("lock=").Append(LockUntilUtcTicks.ToString()).Append("\r\n");
            File.WriteAllText(_filePath, sb.ToString(), Encoding.UTF8);
        }

        public static Config Create(string password, string filePath)
        {
            byte[] salt = new byte[16];
            using (RNGCryptoServiceProvider rng = new RNGCryptoServiceProvider())
            {
                rng.GetBytes(salt);
            }
            Config c = new Config(filePath);
            c.Salt = salt;
            c.Iterations = DefaultIterations;
            c.Hash = Pbkdf2Sha256.Derive(password, salt, c.Iterations, 32);
            c.FailedAttempts = 0;
            c.LockUntilUtcTicks = 0;
            return c;
        }

        public bool Verify(string password)
        {
            byte[] candidate = Pbkdf2Sha256.Derive(password, Salt, Iterations, Hash.Length);
            return Pbkdf2Sha256.FixedTimeEquals(candidate, Hash);
        }

        public bool IsLocked
        {
            get
            {
                if (LockUntilUtcTicks <= 0) return false;
                return DateTime.UtcNow.Ticks < LockUntilUtcTicks;
            }
        }

        public TimeSpan LockRemaining
        {
            get
            {
                long remain = LockUntilUtcTicks - DateTime.UtcNow.Ticks;
                if (remain < 0) remain = 0;
                return TimeSpan.FromTicks(remain);
            }
        }

        /// <summary>记一次失败；满 5 次立即置 15 分钟锁定并把计数归零（锁定期满后重新计 5 次）。</summary>
        public void RegisterFailure()
        {
            FailedAttempts++;
            if (FailedAttempts >= MaxAttempts)
            {
                FailedAttempts = 0;
                LockUntilUtcTicks = DateTime.UtcNow.Add(LockDuration).Ticks;
            }
            Save();
        }

        public void RegisterSuccess()
        {
            FailedAttempts = 0;
            LockUntilUtcTicks = 0;
            Save();
        }
    }

    /// <summary>
    /// PBKDF2-HMAC-SHA256 手工实现：csc.exe 环境下不依赖框架版本的
    /// Rfc2898DeriveBytes(SHA256) 重载，只用到 mscorlib 的 HMACSHA256。
    /// </summary>
    public static class Pbkdf2Sha256
    {
        public static byte[] Derive(string password, byte[] salt, int iterations, int length)
        {
            byte[] pw = Encoding.UTF8.GetBytes(password);
            using (HMACSHA256 hmac = new HMACSHA256(pw))
            {
                byte[] block = new byte[salt.Length + 4];
                Array.Copy(salt, block, salt.Length);
                byte[] result = new byte[length];
                int offset = 0;
                int blockIndex = 1;
                while (offset < length)
                {
                    block[salt.Length] = (byte)(blockIndex >> 24);
                    block[salt.Length + 1] = (byte)(blockIndex >> 16);
                    block[salt.Length + 2] = (byte)(blockIndex >> 8);
                    block[salt.Length + 3] = (byte)blockIndex;
                    byte[] u = hmac.ComputeHash(block);
                    byte[] t = (byte[])u.Clone();
                    for (int i = 1; i < iterations; i++)
                    {
                        u = hmac.ComputeHash(u);
                        for (int j = 0; j < t.Length; j++) t[j] ^= u[j];
                    }
                    int copy = Math.Min(t.Length, length - offset);
                    Array.Copy(t, 0, result, offset, copy);
                    offset += copy;
                    blockIndex++;
                }
                return result;
            }
        }

        public static bool FixedTimeEquals(byte[] a, byte[] b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= (a[i] ^ b[i]);
            return diff == 0;
        }
    }
}
