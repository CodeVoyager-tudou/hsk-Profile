using System;
using System.Drawing;
using System.Windows.Forms;

namespace HiddenDisk
{
    /// <summary>首次运行：设置访问密码（配置写入本实例数据文件夹对应的独立配置文件）。</summary>
    public class SetupForm : Form
    {
        private readonly string _cfgPath;
        private TextBox _pwd;
        private TextBox _pwd2;
        private Button _ok;

        /// <summary>设置成功后由 Program 取走（本次直接进入，不再重复询问密码）。</summary>
        public Config CreatedConfig;

        public SetupForm(string cfgPath)
        {
            _cfgPath = cfgPath;

            Text = "隐藏盘 — 首次设置";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(380, 185);
            Font = new Font("Microsoft YaHei UI", 9F);

            Label title = new Label();
            title.Text = "首次使用，请设置访问密码（用于开启隐藏盘）";
            title.Location = new Point(12, 12);
            title.AutoSize = true;
            Controls.Add(title);

            Label l1 = new Label();
            l1.Text = "密码：";
            l1.Location = new Point(20, 45);
            l1.AutoSize = true;
            _pwd = new TextBox();
            _pwd.Location = new Point(95, 42);
            _pwd.Width = 255;
            _pwd.PasswordChar = '*';
            Controls.Add(l1);
            Controls.Add(_pwd);

            Label l2 = new Label();
            l2.Text = "确认密码：";
            l2.Location = new Point(20, 78);
            l2.AutoSize = true;
            _pwd2 = new TextBox();
            _pwd2.Location = new Point(95, 75);
            _pwd2.Width = 255;
            _pwd2.PasswordChar = '*';
            Controls.Add(l2);
            Controls.Add(_pwd2);

            _ok = new Button();
            _ok.Text = "确定";
            _ok.Location = new Point(275, 115);
            _ok.Width = 75;
            _ok.Click += OnOk;
            AcceptButton = _ok;
            Controls.Add(_ok);

            Label note = new Label();
            note.Text = "注：文件仅隐藏不加密；密码遗失只能删除数据文件夹。";
            note.Location = new Point(12, 152);
            note.AutoSize = true;
            note.ForeColor = Color.Gray;
            Controls.Add(note);
        }

        private void OnOk(object sender, EventArgs e)
        {
            string p = _pwd.Text;
            if (p.Length < 4)
            {
                MessageBox.Show(this, "密码至少 4 位。", "隐藏盘");
                _pwd.Focus();
                return;
            }
            if (p != _pwd2.Text)
            {
                MessageBox.Show(this, "两次输入不一致。", "隐藏盘");
                _pwd2.Text = "";
                _pwd2.Focus();
                return;
            }
            try
            {
                CreatedConfig = Config.Create(p, _cfgPath);
                CreatedConfig.Save();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "保存配置失败：" + ex.Message, "隐藏盘");
                return;
            }
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
