using System;
using System.Collections.Generic;
using System.IO;

namespace HiddenDisk
{
    /// <summary>
    /// 数据文件夹固定在每个物理盘的根目录：D:\HiddenDiskVault 只管 D 盘、E:\HiddenDiskVault 只管 E 盘。
    /// exe 在盘内任何位置启动都对应本盘同一个数据文件夹——移动 exe 不会丢文件视图；
    /// 不同盘的数据文件夹相互独立，可同时各开一个实例、各占一个盘符。
    /// 文件内容不加密（产品决策：仅隐藏 + 密码门禁）。
    /// </summary>
    public static class VaultService
    {
        public const string FolderName = "HiddenDiskVault";

        private static string _directoryOverride = null;

        /// <summary>
        /// 本实例的数据文件夹。exe 在哪个数据盘上，就管理那个盘根的 HiddenDiskVault；
        /// exe 位于系统盘（C 盘）时返回 null —— 按约定 C 盘不创建任何数据，程序会提示改用数据盘副本。
        /// </summary>
        public static string VaultDirectory
        {
            get
            {
                if (_directoryOverride != null) return _directoryOverride;
                string exeDir = AppDomain.CurrentDomain.BaseDirectory;
                string root = Path.GetPathRoot(exeDir);
                if (string.IsNullOrEmpty(root) || root.StartsWith("\\\\"))
                {
                    return Path.Combine(exeDir, FolderName);   // UNC/无盘符环境回退 exe 同目录
                }
                string sysRoot = Path.GetPathRoot(Environment.SystemDirectory);
                if (string.Equals(root, sysRoot, StringComparison.OrdinalIgnoreCase))
                {
                    return null;   // C 盘（系统盘）不创建数据文件夹
                }
                return Path.Combine(root, FolderName);
            }
        }

        /// <summary>旧版（单实例时代）exe 同目录的数据文件夹，仅用于一次性自动迁移。</summary>
        public static string LegacyVaultDirectory
        {
            get { return Path.Combine(AppDomain.CurrentDomain.BaseDirectory, FolderName); }
        }

        /// <summary>仅供 --selftest 重定向数据文件夹位置，正式运行不调用。</summary>
        public static void SetDirectoryForTests(string dir)
        {
            _directoryOverride = dir;
        }

        /// <summary>
        /// 旧数据文件夹迁移到新位置：仅当旧位置存在、新位置尚未创建、二者不同时执行
        /// （同卷改名，瞬间完成且保留 Hidden+System 属性）。返回是否发生了迁移。
        /// </summary>
        public static bool MigrateLegacyVault(string oldDir, string newDir)
        {
            string o = Path.GetFullPath(oldDir).TrimEnd('\\');
            string n = Path.GetFullPath(newDir).TrimEnd('\\');
            if (string.Equals(o, n, StringComparison.OrdinalIgnoreCase)) return false;
            if (!Directory.Exists(o) || Directory.Exists(n)) return false;
            Directory.Move(o, n);
            return true;
        }

        /// <summary>本实例管理的物理盘标签（如 "D:"），用于提示与跨盘拒绝文案。</summary>
        public static string ManagedVolumeLabel()
        {
            string r = Path.GetPathRoot(Path.GetFullPath(VaultDirectory));
            if (r == null) return "?";
            return r.TrimEnd('\\');
        }

        /// <summary>确保数据文件夹存在并带 Hidden+System 属性；失败抛异常由调用方提示。</summary>
        public static void EnsureVault()
        {
            string dir = VaultDirectory;
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
            File.SetAttributes(dir, FileAttributes.Directory | FileAttributes.Hidden | FileAttributes.System);
        }

        /// <summary>
        /// 把若干文件/文件夹移入数据文件夹。返回成功移入的数量；
        /// 单项失败记入 errors 并继续处理其余；已在盘内的项目视为无操作（不计成功不报错）；
        /// 非本盘（跨卷）的项目直接拒绝——每个实例只管理自己所在物理盘的数据。
        /// </summary>
        public static int MoveIntoVault(IEnumerable<string> paths, List<string> errors)
        {
            int ok = 0;
            foreach (string path in paths)
            {
                try
                {
                    if (MoveOne(path)) ok++;
                }
                catch (Exception ex)
                {
                    if (errors != null) errors.Add(GetDisplayName(path) + "：" + ex.Message);
                }
            }
            return ok;
        }

        private static bool MoveOne(string src)
        {
            string fullSrc = Path.GetFullPath(src);
            string vault = Path.GetFullPath(VaultDirectory);

            // 安全护栏：不移动整个磁盘分区，不隐藏数据文件夹自身或其上级目录
            string root = Path.GetPathRoot(fullSrc);
            if (string.Equals(root.TrimEnd('\\'), fullSrc.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("不能移动整个磁盘分区");
            if (string.Equals(fullSrc.TrimEnd('\\'), vault.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)
                || vault.StartsWith(fullSrc.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("不能隐藏数据文件夹自身或其上级目录");

            // 本实例只管理本盘数据：跨盘项目拒绝（需使用对应盘上的程序副本）
            if (!SameVolume(fullSrc, vault))
                throw new InvalidOperationException("只能隐藏 " + ManagedVolumeLabel()
                    + " 盘上的文件，请在对应盘上使用程序副本");

            if (!File.Exists(src) && !Directory.Exists(src)) return false;
            // 已在数据文件夹内的项目（从盘符里拖回来的）不再处理
            if (fullSrc.TrimEnd('\\').StartsWith(vault.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase))
                return false;

            string name = GetDisplayName(src);
            if (name.Length == 0) name = "item_" + DateTime.Now.Ticks.ToString();
            string dst = Path.Combine(VaultDirectory, UniqueName(VaultDirectory, name));

            if (Directory.Exists(src)) Directory.Move(src, dst);
            else File.Move(src, dst);
            return true;
        }

        private static bool SameVolume(string a, string b)
        {
            string ra = Path.GetPathRoot(a);
            string rb = Path.GetPathRoot(b);
            return string.Equals(ra, rb, StringComparison.OrdinalIgnoreCase);
        }

        private static string GetDisplayName(string path)
        {
            string name = Path.GetFileName(path.TrimEnd('\\', '/'));
            if (name == null) name = "";
            return name;
        }

        /// <summary>目标目录内重名时自动加 " (2)"、" (3)" 序号。</summary>
        public static string UniqueName(string dir, string name)
        {
            string candidate = name;
            int n = 2;
            while (File.Exists(Path.Combine(dir, candidate)) || Directory.Exists(Path.Combine(dir, candidate)))
            {
                string ext = Path.GetExtension(name);
                string stem = ext.Length > 0 ? name.Substring(0, name.Length - ext.Length) : name;
                candidate = stem + " (" + n.ToString() + ")" + ext;
                n++;
                if (n > 999)
                {
                    candidate = name + "." + Guid.NewGuid().ToString("N").Substring(0, 8);
                    break;
                }
            }
            return candidate;
        }
    }
}
