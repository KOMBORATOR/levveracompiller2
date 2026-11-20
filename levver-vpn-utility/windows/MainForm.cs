using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace LevverVpn.Windows;

public sealed class MainForm : Form
{
    private readonly TextBox source = new() { Multiline = true, ScrollBars = ScrollBars.Vertical, PlaceholderText = "Вставьте VLESS / VMess / Trojan / Shadowsocks / Hysteria2 ссылку или sing-box JSON", Dock = DockStyle.Fill };
    private readonly ListBox profiles = new() { Dock = DockStyle.Fill, IntegralHeight = false };
    private readonly Label status = new() { Text = "Отключено", AutoSize = true, ForeColor = Color.LightSkyBlue };
    private readonly Button connect = new() { Text = "ПОДКЛЮЧИТЬ", Height = 42, Dock = DockStyle.Fill };
    private readonly List<ProfileItem> items = new();
    private Process? core;
    private string? configPath;
    private bool connected;

    public MainForm()
    {
        Text = "LEVVER VPN UTILITY";
        Width = 900; Height = 650; MinimumSize = new Size(720, 520);
        BackColor = Color.FromArgb(12, 18, 30); ForeColor = Color.White;
        Font = new Font("Segoe UI", 10);
        BuildLayout();
        FormClosing += (_, _) => StopCore();
        connect.Click += async (_, _) => { if (connected) StopCore(); else await ConnectSelected(); };
    }

    private void BuildLayout()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(22), ColumnCount = 2, RowCount = 4 };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 72));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 56));
        var heading = new Label { Text = "LEVVER VPN UTILITY\nWINDOWS • 1.0.0", Dock = DockStyle.Fill, Font = new Font("Segoe UI Semibold", 19), ForeColor = Color.FromArgb(115, 190, 255) };
        root.Controls.Add(heading, 0, 0); root.SetColumnSpan(heading, 2);
        var left = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 3 };
        left.RowStyles.Add(new RowStyle(SizeType.Absolute, 32)); left.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); left.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        left.Controls.Add(new Label { Text = "ИМПОРТ ПРОФИЛЯ ИЛИ ПОДПИСКИ", ForeColor = Color.LightGray, Dock = DockStyle.Fill }, 0, 0);
        left.Controls.Add(source, 0, 1);
        var import = new Button { Text = "ДОБАВИТЬ / ИМПОРТИРОВАТЬ", Dock = DockStyle.Fill, Height = 40 };
        import.Click += async (_, _) => await ImportSource();
        left.Controls.Add(import, 0, 2);
        root.Controls.Add(left, 0, 1);
        var right = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2 };
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 32)); right.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        right.Controls.Add(new Label { Text = "СЕРВЕРЫ", ForeColor = Color.LightGray, Dock = DockStyle.Fill }, 0, 0);
        right.Controls.Add(profiles, 0, 1);
        root.Controls.Add(right, 1, 1);
        root.Controls.Add(status, 0, 2);
        root.Controls.Add(connect, 1, 2);
        var footer = new Label { Text = "Подключение через sing-box • Используйте только серверы, на которые у вас есть разрешение.", Dock = DockStyle.Fill, ForeColor = Color.Gray, TextAlign = ContentAlignment.MiddleLeft };
        root.Controls.Add(footer, 0, 3); root.SetColumnSpan(footer, 2);
        Controls.Add(root);
        foreach (Control c in new Control[] { source, profiles, connect }) { c.BackColor = Color.FromArgb(23, 33, 51); c.ForeColor = Color.White; }
        import.BackColor = Color.FromArgb(32, 80, 130); import.ForeColor = Color.White;
        connect.BackColor = Color.FromArgb(24, 112, 178);
    }

    private async Task ImportSource()
    {
        try
        {
            var text = source.Text.Trim();
            if (text.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
                text = await http.GetStringAsync(text);
            }
            var parsed = ParseInput(text);
            if (parsed.Count == 0) throw new InvalidOperationException("Не найдено поддерживаемых серверов или конфигурации.");
            foreach (var p in parsed) { items.Add(p); profiles.Items.Add(p.Name); }
            status.Text = $"Импортировано серверов: {parsed.Count}";
        }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Ошибка импорта", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }

    private static List<ProfileItem> ParseInput(string input)
    {
        if (input.StartsWith("{"))
        {
            var root = JsonNode.Parse(input) as JsonObject ?? throw new InvalidDataException("Некорректный JSON.");
            var outboundArray = root["outbounds"] as JsonArray;
            if (outboundArray is null) return new() { new ProfileItem("Импортированный sing-box JSON", root.ToJsonString()) };
            var list = new List<ProfileItem>();
            foreach (var node in outboundArray.OfType<JsonObject>())
            {
                var type = (string?)node["type"] ?? "";
                if (type is "direct" or "block" or "dns") continue;
                var single = (JsonObject)root.DeepClone();
                var proxy = (JsonObject)node.DeepClone();
                proxy["tag"] ??= "proxy";
                single["outbounds"] = new JsonArray(proxy, new JsonObject { ["type"] = "direct", ["tag"] = "direct" });
                single["route"] ??= new JsonObject();
                single["route"]!["final"] = (string?)node["tag"] ?? "proxy";
                single["inbounds"] ??= new JsonArray();
                if (!(single["inbounds"] as JsonArray)!.OfType<JsonObject>().Any(x => (string?)x["type"] == "tun"))
                    (single["inbounds"] as JsonArray)!.Add(new JsonObject { ["type"]="tun", ["tag"]="tun-in", ["interface_name"]="LEVVER", ["address"]=new JsonArray("172.19.0.1/30"), ["auto_route"]=true, ["strict_route"]=true });
                list.Add(new ProfileItem((string?)node["tag"] ?? type, single.ToJsonString()));
            }
            return list;
        }
        if (!input.Contains("://") && input.Length > 30)
        {
            try { input = Encoding.UTF8.GetString(Convert.FromBase64String(input)); } catch { }
        }
        var result = new List<ProfileItem>();
        foreach (var line in input.Split(new[] { '\\r', '\\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (line.StartsWith("#") || line.StartsWith("https://")) continue;
            var uri = BuildUriConfig(line);
            if (uri is not null) result.Add(uri);
        }
        return result;
    }

    private static ProfileItem? BuildUriConfig(string raw)
    {
        try
        {
            if (raw.StartsWith("ss://", StringComparison.OrdinalIgnoreCase) && !raw.Contains('@'))
            {
                var fragmentAt = raw.IndexOf('#');
                var fragment = fragmentAt >= 0 ? raw[fragmentAt..] : "";
                var encoded = raw[5..].Split('#','?')[0].TrimEnd('/');
                encoded = encoded.Replace('-', '+').Replace('_', '/');
                encoded = encoded.PadRight(encoded.Length + (4 - encoded.Length % 4) % 4, '=');
                var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(encoded));
                if (decoded.Contains('@')) return BuildUriConfig("ss://" + decoded + fragment);
            }
            var u = new Uri(raw);
            var scheme = u.Scheme.ToLowerInvariant();
            var q = System.Web.HttpUtility.ParseQueryString(u.Query);
            var host = u.Host; var port = u.Port;
            JsonObject outbound = new() { ["server"] = host, ["server_port"] = port, ["tag"] = "proxy" };
            switch (scheme)
            {
                case "vless":
                    outbound["type"]="vless"; outbound["uuid"]=Uri.UnescapeDataString(u.UserInfo);
                    break;
                case "trojan":
                    outbound["type"]="trojan"; outbound["password"]=Uri.UnescapeDataString(u.UserInfo);
                    break;
                case "hysteria2": case "hy2":
                    outbound["type"]="hysteria2"; outbound["password"]=Uri.UnescapeDataString(u.UserInfo);
                    break;
                case "hysteria":
                    outbound["type"]="hysteria";
                    outbound["up_mbps"]=int.TryParse(q["upmbps"] ?? q["up"],out var up)?up:10;
                    outbound["down_mbps"]=int.TryParse(q["downmbps"] ?? q["down"],out var down)?down:50;
                    if (!string.IsNullOrWhiteSpace(q["auth"])) outbound["auth_str"]=q["auth"];
                    break;
                case "ss": case "shadowsocks":
                    var user = Uri.UnescapeDataString(u.UserInfo);
                    if (!user.Contains(':') && !string.IsNullOrWhiteSpace(user))
                        user = Encoding.UTF8.GetString(Convert.FromBase64String(user));
                    var split = user.IndexOf(':');
                    if (split <= 0) return null;
                    outbound["type"]="shadowsocks";
                    outbound["method"]=user[..split];
                    outbound["password"]=user[(split+1)..];
                    break;
                case "socks": case "socks5":
                    outbound["type"]="socks";
                    if (!string.IsNullOrEmpty(u.UserInfo))
                    {
                        var auth=u.UserInfo.Split(':',2);
                        if (auth.Length>0) outbound["username"]=Uri.UnescapeDataString(auth[0]);
                        if (auth.Length>1) outbound["password"]=Uri.UnescapeDataString(auth[1]);
                    }
                    break;
                case "vmess":
                    var payload = Encoding.UTF8.GetString(Convert.FromBase64String(raw[(raw.IndexOf("://")+3)..].TrimEnd('/')));
                    using (var doc = JsonDocument.Parse(payload))
                    {
                        var v=doc.RootElement; outbound["type"]="vmess"; outbound["server"]=v.GetProperty("add").GetString();
                        outbound["server_port"]=int.Parse(v.GetProperty("port").GetString() ?? "443");
                        outbound["uuid"]=v.GetProperty("id").GetString(); outbound["security"]=v.TryGetProperty("scy",out var scy)?scy.GetString():"auto";
                    }
                    break;
                default: return null;
            }
            if (q["security"]=="tls" || q["tls"]=="1" || scheme is "trojan" or "hysteria2" or "hy2" or "hysteria")
                outbound["tls"]=new JsonObject { ["enabled"]=true, ["server_name"]=q["sni"] ?? q["servername"] ?? host, ["insecure"]=q["allowInsecure"]=="1" };
            if (scheme=="vless" && !string.IsNullOrWhiteSpace(q["flow"])) outbound["flow"]=q["flow"];
            if (q["type"]=="ws")
                outbound["transport"]=new JsonObject { ["type"]="ws", ["path"]=q["path"] ?? "/", ["headers"]=new JsonObject { ["Host"]=q["host"] ?? host } };
            var config=new JsonObject {
                ["log"]=new JsonObject { ["level"]="warn" },
                ["inbounds"]=new JsonArray(new JsonObject { ["type"]="tun", ["tag"]="tun-in", ["interface_name"]="LEVVER", ["address"]=new JsonArray("172.19.0.1/30"), ["auto_route"]=true, ["strict_route"]=true }),
                ["outbounds"]=new JsonArray(outbound,new JsonObject { ["type"]="direct", ["tag"]="direct" }),
                ["route"]=new JsonObject { ["final"]="proxy", ["auto_detect_interface"]=true }
            };
            var name=Uri.UnescapeDataString(u.Fragment.TrimStart('#'));
            return new ProfileItem(string.IsNullOrWhiteSpace(name)?$"{scheme.ToUpperInvariant()} • {host}":name,config.ToJsonString());
        }
        catch { return null; }
    }

    private async Task ConnectSelected()
    {
        if (profiles.SelectedIndex < 0) { MessageBox.Show("Сначала выберите сервер."); return; }
        try
        {
            var exe=Path.Combine(AppContext.BaseDirectory,"sing-box.exe");
            if (!File.Exists(exe)) throw new FileNotFoundException("Не найден sing-box.exe рядом с приложением.");
            configPath=Path.Combine(Path.GetTempPath(),"levver-singbox.json");
            await File.WriteAllTextAsync(configPath,items[profiles.SelectedIndex].Config);
            var check=Process.Start(new ProcessStartInfo(exe,$"check -c \"{configPath}\"") { UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true })!;
            var errors=await check.StandardError.ReadToEndAsync(); await check.WaitForExitAsync();
            if (check.ExitCode!=0) throw new InvalidOperationException(errors);
            core=Process.Start(new ProcessStartInfo(exe,$"run -c \"{configPath}\"") { UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=AppContext.BaseDirectory })!;
            connected=true; connect.Text="ОТКЛЮЧИТЬ"; status.Text="Запуск ядра…";
            await Task.Delay(1200);
            if (core.HasExited) throw new InvalidOperationException("sing-box завершился. Проверьте конфигурацию и драйвер TUN.");
            status.Text="Подключено • sing-box работает";
        }
        catch(Exception ex) { StopCore(); MessageBox.Show(this,ex.Message,"Ошибка подключения",MessageBoxButtons.OK,MessageBoxIcon.Error); }
    }

    private void StopCore()
    {
        try { if(core is { HasExited:false }) { core.Kill(true); core.WaitForExit(3000); } } catch { }
        core?.Dispose(); core=null; connected=false; connect.Text="ПОДКЛЮЧИТЬ"; status.Text="Отключено";
        if(configPath is not null) try { File.Delete(configPath); } catch { }
    }
    private sealed record ProfileItem(string Name,string Config);
}
