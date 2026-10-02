using System;
using System.Drawing;
using System.Windows.Forms;

namespace HiddenDisk
{
    /// <summary>
    /// 密码门禁：5 次试错，错满 5 次锁 15 分钟（倒计时展示）。
    /// 试错与锁定状态在 Config 中持久化，重启程序不可绕过。
    /// </summary>
    public class PasswordForm : Form
    {
        private readonly Config _cfg;
        private TextBox _pwd;
        private Button _ok;
        private Label _state;
        private Timer _timer;

        public PasswordForm(Config cfg)
        {
            _cfg = cfg;

            Text = "隐藏盘";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(340, 125);
            Font = new Font("Microsoft YaHei UI", 9F);

            Label tip = new Label();
            tip.Text = "请输入访问密码：";
            tip.Location = new Point(15, 15);
            tip.AutoSize = true;
            Controls.Add(tip);

            _pwd = new TextBox();
            _pwd.Location = new Point(15, 40);
            _pwd.Width = 220;
            _pwd.PasswordChar = '*';
            Controls.Add(_pwd);

            _ok = new Button();
            _ok.Text = "解锁";
            _ok.Location = new Point(245, 38);
            _ok.Width = 80;
            _ok.Click += delegate { TryUnlock(); };
            AcceptButton = _ok;
            Controls.Add(_ok);

            _state = new Label();
            _state.Location = new Point(15, 80);
            _state.AutoSize = true;
            _state.ForeColor = Color.Firebrick;
            Controls.Add(_state);

            _timer = new Timer();
            _timer.Interval = 500;
            _timer.Tick += delegate { UpdateLockState(); };
            _timer.Start();
            UpdateLockState();
        }

        private void UpdateLockState()
        {
            if (_cfg.IsLocked)
            {
                TimeSpan r = _cfg.LockRemaining;
                _state.Text = string.Format("已锁定，剩余 {0}:{1:00} 后可重试",
                    (int)r.TotalMinutes, r.Seconds);
                _pwd.Enabled = false;
                _ok.Enabled = false;
                return;
            }
            if (!_pwd.Enabled)
            {
                _pwd.Enabled = true;
                _ok.Enabled = true;
            }
            int left = Config.MaxAttempts - _cfg.FailedAttempts;
            _state.Text = "剩余尝试次数：" + left.ToString();
        }

        private void TryUnlock()
        {
            if (_cfg.IsLocked) return;
            if (_cfg.Verify(_pwd.Text))
            {
                _cfg.RegisterSuccess();
                _timer.Stop();
                DialogResult = DialogResult.OK;
                Close();
                return;
            }
            _cfg.RegisterFailure();
            _pwd.Text = "";
            UpdateLockState();
            if (_cfg.IsLocked)
            {
                MessageBox.Show(this, "连续错误 5 次，已锁定 15 分钟。", "隐藏盘");
            }
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            base.OnFormClosed(e);
            _timer.Stop();
            _timer.Dispose();
        }
    }
}
