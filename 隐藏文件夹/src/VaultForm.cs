using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace HiddenDisk
{
    /// <summary>
    /// 密码验证通过后的控制窗：打开盘符 / 关闭并退出 / 拖入=移入隐藏。
    /// 拖放采用 Copy 效果 + 本程序自行删除源文件，保证精确一次的「移动」语义，
    /// 避免 OLE optimistic move 下资源管理器二次删除导致弹错。
    /// </summary>
    public class VaultForm : Form
    {
        private readonly string _letter;
        private readonly string _vaultDir;
        private Label _status;

        public VaultForm(string letter, string vaultDir)
        {
            _letter = letter;
            _vaultDir = vaultDir;

            Text = "隐藏盘（" + letter + "）";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(470, 205);
            Font = new Font("Microsoft YaHei UI", 9F);
            AllowDrop = true;
            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;

            Label info = new Label();
            info.Text = "盘符 " + letter + " 已开启";
            info.Location = new Point(15, 15);
            info.AutoSize = true;
            info.Font = new Font("Microsoft YaHei UI", 10.5F, FontStyle.Bold);
            Controls.Add(info);

            Label path = new Label();
            path.Text = "数据位置：" + vaultDir + "（只管理 " + VaultService.ManagedVolumeLabel() + " 盘数据）";
            path.Location = new Point(15, 44);
            path.Width = 440;
            path.Height = 18;
            path.AutoEllipsis = true;
            path.ForeColor = Color.Gray;
            Controls.Add(path);

            Label hint = new Label();
            hint.Text = "拖入 " + VaultService.ManagedVolumeLabel() + " 盘的文件/文件夹即可隐藏（原位置消失，见 " + letter + "）。";
            hint.Location = new Point(15, 68);
            hint.AutoSize = true;
            Controls.Add(hint);

            _status = new Label();
            _status.Text = " ";
            _status.Location = new Point(15, 96);
            _status.AutoSize = true;
            _status.ForeColor = Color.Green;
            Controls.Add(_status);

            Button open = new Button();
            open.Text = "打开盘符";
            open.Location = new Point(15, 158);
            open.Width = 110;
            open.Click += delegate
            {
                try { System.Diagnostics.Process.Start("explorer.exe", letter + "\\"); }
                catch (Exception ex) { MessageBox.Show(this, "打开失败：" + ex.Message, "隐藏盘"); }
            };
            Controls.Add(open);

            Button closeBtn = new Button();
            closeBtn.Text = "关闭并退出";
            closeBtn.Location = new Point(305, 158);
            closeBtn.Width = 110;
            closeBtn.Click += delegate { Close(); };
            Controls.Add(closeBtn);
        }

        private void OnDragEnter(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
                e.Effect = DragDropEffects.Copy;
            else
                e.Effect = DragDropEffects.None;
        }

        private void OnDragDrop(object sender, DragEventArgs e)
        {
            string[] paths = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (paths == null || paths.Length == 0) return;

            Cursor = Cursors.WaitCursor;
            try
            {
                List<string> errors = new List<string>();
                int ok = VaultService.MoveIntoVault(paths, errors);
                _status.Text = "已隐藏 " + ok.ToString() + " 个项目"
                    + (errors.Count > 0 ? "（" + errors.Count.ToString() + " 个失败）" : "");
                if (errors.Count > 0)
                    MessageBox.Show(this, "以下项目未能隐藏：\r\n" + string.Join("\r\n", errors.ToArray()), "隐藏盘");
            }
            finally
            {
                Cursor = Cursors.Default;
            }
        }
    }
}
