param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("list", "join", "create", "leave", "show")]
    [string]$Action,
    [string]$Name = "",
    [string]$Password = ""
)

Add-Type -ReferencedAssemblies "UIAutomationClient", "UIAutomationTypes" @"
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Automation;

public class RadminAutomationCore {
    [DllImport("user32.dll")] public static extern IntPtr OpenDesktop(string d, uint f, bool i, uint a);
    [DllImport("user32.dll")] public static extern bool SetThreadDesktop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool CloseDesktop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    public class StatusInfo {
        public bool installed;
        public bool running;
        public string alias = "";
        public string ip = "";
        public string status = "";
        public List<string> networks = new List<string>();
        public string error = "";
    }

    public class ActionResult {
        public bool ok;
        public string message = "";
    }

    private static AutomationElement FindMainWindow(int pid) {
        AutomationElement root = AutomationElement.RootElement;
        Condition pidCond = new PropertyCondition(AutomationElement.ProcessIdProperty, pid);
        AutomationElementCollection wins = root.FindAll(TreeScope.Children, pidCond);
        foreach (AutomationElement w in wins) {
            if (w.Current.AutomationId == "MainWindow") {
                return w;
            }
        }
        return null;
    }

    public static StatusInfo QueryStatus(int pid) {
        var info = new StatusInfo { installed = true };
        IntPtr hDesk = OpenDesktop("Default", 0, false, 0x1FF);
        Thread t = new Thread(() => {
            SetThreadDesktop(hDesk);
            AutomationElement w = FindMainWindow(pid);
            if (w == null) {
                info.running = false;
                info.error = "MainWindow not found";
                return;
            }
            info.running = true;

            try {
                AutomationElement aliasEl = w.FindFirst(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.centralWidget.mainContentWidget.userInfoWidget.LAlias"));
                if (aliasEl != null) info.alias = aliasEl.Current.Name;

                AutomationElement ipEl = w.FindFirst(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.centralWidget.mainContentWidget.userInfoWidget.LIP"));
                if (ipEl != null) info.ip = ipEl.Current.Name;

                AutomationElement stEl = w.FindFirst(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.centralWidget.mainContentWidget.userInfoWidget.LStatus"));
                if (stEl != null) info.status = stEl.Current.Name;

                AutomationElementCollection allDesc = w.FindAll(TreeScope.Descendants, Condition.TrueCondition);
                for (int i = 0; i < allDesc.Count; i++) {
                    AutomationElement el = allDesc[i];
                    if (el.Current.AutomationId.EndsWith(".NetworkName")) {
                        string n = el.Current.Name;
                        if (!string.IsNullOrWhiteSpace(n) && !info.networks.Contains(n)) {
                            info.networks.Add(n);
                        }
                    }
                }
            } catch (Exception ex) {
                info.error = ex.Message;
            }
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        CloseDesktop(hDesk);
        return info;
    }

    public static ActionResult Join(int pid, string name, string pass) {
        var res = new ActionResult();
        IntPtr hDesk = OpenDesktop("Default", 0, false, 0x1FF);
        Thread t = new Thread(() => {
            SetThreadDesktop(hDesk);
            AutomationElement root = AutomationElement.RootElement;
            AutomationElement w = FindMainWindow(pid);
            if (w == null) { res.ok = false; res.message = "Radmin não está aberto."; return; }

            ShowWindow((IntPtr)w.Current.NativeWindowHandle, 9);
            SetForegroundWindow((IntPtr)w.Current.NativeWindowHandle);
            Thread.Sleep(200);

            AutomationElement redeMenu = w.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.NameProperty, "Rede"));
            if (redeMenu == null) { res.ok = false; res.message = "Menu Rede não encontrado."; return; }

            ((ExpandCollapsePattern)redeMenu.GetCurrentPattern(ExpandCollapsePattern.Pattern)).Expand();
            Thread.Sleep(200);

            AutomationElement joinAction = w.FindFirst(TreeScope.Descendants, 
                new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.actionJoin_Existant"));
            if (joinAction == null) { res.ok = false; res.message = "Ação Conectar à rede não encontrada."; return; }

            ((InvokePattern)joinAction.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
            Thread.Sleep(600);

            AutomationElement dlg = w.FindFirst(TreeScope.Descendants, 
                new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.DlgJoinNetwork"));
            if (dlg == null) { res.ok = false; res.message = "Janela de conexão não abriu."; return; }

            AutomationElement editName = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgJoinNetwork.centralWidget.mainContentWidget.tabWidgetNetSelector.qt_tabwidget_stackedwidget.tab_private.LENetName"));
            AutomationElement editPass = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgJoinNetwork.centralWidget.mainContentWidget.tabWidgetNetSelector.qt_tabwidget_stackedwidget.tab_private.LEPassword"));
            AutomationElement joinBtn = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgJoinNetwork.centralWidget.mainContentWidget.joinPushButton"));
            AutomationElement cancelBtn = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgJoinNetwork.centralWidget.mainContentWidget.cancelPushButton"));

            if (editName == null || editPass == null || joinBtn == null) {
                if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                res.ok = false; res.message = "Campos de entrada não encontrados.";
                return;
            }

            ((ValuePattern)editName.GetCurrentPattern(ValuePattern.Pattern)).SetValue(name);
            ((ValuePattern)editPass.GetCurrentPattern(ValuePattern.Pattern)).SetValue(pass);
            Thread.Sleep(200);
            ((InvokePattern)joinBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();

            for (int i = 0; i < 20; i++) {
                Thread.Sleep(200);
                AutomationElement checkDlg = w.FindFirst(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.DlgJoinNetwork"));
                if (checkDlg == null) {
                    res.ok = true;
                    res.message = "Entrada na rede concluída com sucesso.";
                    return;
                }

                // Check for tooltip error popup on desktop root
                AutomationElement tip = root.FindFirst(TreeScope.Children, new PropertyCondition(AutomationElement.ClassNameProperty, "QTipLabel"));
                if (tip != null && !string.IsNullOrWhiteSpace(tip.Current.Name)) {
                    res.ok = false;
                    res.message = tip.Current.Name;
                    if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                    return;
                }

                AutomationElementCollection allLabels = checkDlg.FindAll(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Text));
                foreach (AutomationElement lbl in allLabels) {
                    string txt = lbl.Current.Name.ToLower();
                    if (txt.Contains("incorret") || txt.Contains("não encontrad") || txt.Contains("inválid") || txt.Contains("erro")) {
                        res.ok = false;
                        res.message = lbl.Current.Name;
                        if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                        return;
                    }
                }
            }

            if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
            res.ok = false;
            res.message = "Tempo limite aguardando confirmação do Radmin.";
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        CloseDesktop(hDesk);
        return res;
    }

    public static ActionResult Create(int pid, string name, string pass) {
        var res = new ActionResult();
        IntPtr hDesk = OpenDesktop("Default", 0, false, 0x1FF);
        Thread t = new Thread(() => {
            SetThreadDesktop(hDesk);
            AutomationElement root = AutomationElement.RootElement;
            AutomationElement w = FindMainWindow(pid);
            if (w == null) { res.ok = false; res.message = "Radmin não está aberto."; return; }

            ShowWindow((IntPtr)w.Current.NativeWindowHandle, 9);
            SetForegroundWindow((IntPtr)w.Current.NativeWindowHandle);
            Thread.Sleep(200);

            AutomationElement redeMenu = w.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.NameProperty, "Rede"));
            if (redeMenu == null) { res.ok = false; res.message = "Menu Rede não encontrado."; return; }

            ((ExpandCollapsePattern)redeMenu.GetCurrentPattern(ExpandCollapsePattern.Pattern)).Expand();
            Thread.Sleep(200);

            AutomationElement createAction = w.FindFirst(TreeScope.Descendants, 
                new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.actionCreate_New"));
            if (createAction == null) { res.ok = false; res.message = "Ação Criar rede não encontrada."; return; }

            ((InvokePattern)createAction.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
            Thread.Sleep(600);

            AutomationElement dlg = w.FindFirst(TreeScope.Descendants, 
                new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.DlgCreateNetwork"));
            if (dlg == null) { res.ok = false; res.message = "Janela de criação não abriu."; return; }

            AutomationElement editName = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgCreateNetwork.mainContentWidget.LENetName"));
            AutomationElement editPass = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgCreateNetwork.mainContentWidget.LEPassword"));
            AutomationElement editPassConfirm = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgCreateNetwork.mainContentWidget.LEPasswordConfirm"));
            AutomationElement createBtn = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgCreateNetwork.mainContentWidget.createPushButton"));
            AutomationElement cancelBtn = dlg.FindFirst(TreeScope.Descendants,
                new PropertyCondition(AutomationElement.AutomationIdProperty, 
                "MainWindow.DlgCreateNetwork.mainContentWidget.cancelPushButton"));

            if (editName == null || editPass == null || editPassConfirm == null || createBtn == null) {
                if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                res.ok = false; res.message = "Campos de criação não encontrados.";
                return;
            }

            ((ValuePattern)editName.GetCurrentPattern(ValuePattern.Pattern)).SetValue(name);
            ((ValuePattern)editPass.GetCurrentPattern(ValuePattern.Pattern)).SetValue(pass);
            ((ValuePattern)editPassConfirm.GetCurrentPattern(ValuePattern.Pattern)).SetValue(pass);
            Thread.Sleep(200);
            ((InvokePattern)createBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();

            for (int i = 0; i < 20; i++) {
                Thread.Sleep(200);
                AutomationElement checkDlg = w.FindFirst(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, "MainWindow.DlgCreateNetwork"));
                if (checkDlg == null) {
                    res.ok = true;
                    res.message = "Rede criada com sucesso.";
                    return;
                }

                // Check for tooltip error popup on desktop root
                AutomationElement tip = root.FindFirst(TreeScope.Children, new PropertyCondition(AutomationElement.ClassNameProperty, "QTipLabel"));
                if (tip != null && !string.IsNullOrWhiteSpace(tip.Current.Name)) {
                    res.ok = false;
                    res.message = tip.Current.Name;
                    if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                    return;
                }

                AutomationElementCollection allLabels = checkDlg.FindAll(TreeScope.Descendants, 
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Text));
                foreach (AutomationElement lbl in allLabels) {
                    string txt = lbl.Current.Name.ToLower();
                    if (txt.Contains("já existe") || txt.Contains("inválid") || txt.Contains("erro") || txt.Contains("curta")) {
                        res.ok = false;
                        res.message = lbl.Current.Name;
                        if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                        return;
                    }
                }
            }

            if (cancelBtn != null) ((InvokePattern)cancelBtn.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
            res.ok = false;
            res.message = "Tempo limite aguardando criação da rede.";
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        CloseDesktop(hDesk);
        return res;
    }

    public static ActionResult Leave(int pid, string name) {
        var res = new ActionResult();
        IntPtr hDesk = OpenDesktop("Default", 0, false, 0x1FF);
        Thread t = new Thread(() => {
            SetThreadDesktop(hDesk);
            AutomationElement w = FindMainWindow(pid);
            if (w == null) { res.ok = false; res.message = "Radmin não está aberto."; return; }

            ShowWindow((IntPtr)w.Current.NativeWindowHandle, 9);
            SetForegroundWindow((IntPtr)w.Current.NativeWindowHandle);
            Thread.Sleep(200);

            AutomationElementCollection allDesc = w.FindAll(TreeScope.Descendants, Condition.TrueCondition);
            AutomationElement targetHeader = null;
            for (int i = 0; i < allDesc.Count; i++) {
                AutomationElement el = allDesc[i];
                if (el.Current.AutomationId.EndsWith(".NetworkName") && el.Current.Name.Equals(name, StringComparison.OrdinalIgnoreCase)) {
                    targetHeader = el;
                    break;
                }
            }

            if (targetHeader == null) {
                res.ok = true;
                res.message = "A rede já não está conectada no Radmin.";
                return;
            }

            // Click AdvancedDots in same parent container
            AutomationElement container = TreeWalker.RawViewWalker.GetParent(targetHeader);
            if (container != null) {
                AutomationElement dots = container.FindFirst(TreeScope.Children, 
                    new PropertyCondition(AutomationElement.AutomationIdProperty, 
                    "MainWindow.centralWidget.mainContentWidget.stackedWidget.pageNetworksTree.scrollArea.qt_scrollarea_viewport.scrollAreaWidgetContents.CNetworkWidget.NetworkWidget.AdvancedDots"));
                if (dots != null) {
                    ((InvokePattern)dots.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                    Thread.Sleep(300);

                    // Look for context menu
                    AutomationElement root = AutomationElement.RootElement;
                    AutomationElementCollection menus = root.FindAll(TreeScope.Children, new PropertyCondition(AutomationElement.ProcessIdProperty, pid));
                    foreach (AutomationElement m in menus) {
                        if (m.Current.ClassName.Contains("Menu")) {
                            AutomationElement leaveItem = m.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.NameProperty, "Sair da rede"));
                            if (leaveItem != null) {
                                ((InvokePattern)leaveItem.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                                Thread.Sleep(500);
                                res.ok = true;
                                res.message = "Saída da rede solicitada com sucesso.";
                                return;
                            }
                        }
                    }
                }
            }

            res.ok = false;
            res.message = "Não foi possível acionar a opção Sair da rede.";
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        CloseDesktop(hDesk);
        return res;
    }
}
"@

function Get-RadminProcess {
    $p = Get-Process RvRvpnGui -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $p) {
        $exePath = "C:\Program Files (x86)\Radmin VPN\RvRvpnGui.exe"
        if (Test-Path $exePath) {
            Start-Process $exePath -ArgumentList "/show"
            Start-Sleep -Milliseconds 800
            $p = Get-Process RvRvpnGui -ErrorAction SilentlyContinue | Select-Object -First 1
        }
    }
    return $p
}

$proc = Get-RadminProcess

switch ($Action) {
    "list" {
        if (-not $proc) {
            # Try reading registry as fallback
            $known = @()
            Get-Item -Path "HKCU:\Software\Famatech\Radmin VPN\ui\networks\*" -ErrorAction SilentlyContinue | ForEach-Object { $known += $_.PSChildName }
            [PSCustomObject]@{
                installed = $true
                running = $false
                alias = ""
                ip = ""
                status = "Offline"
                networks = @()
                knownNetworks = $known
                error = "RvRvpnGui is not running"
            } | ConvertTo-Json -Compress
            exit 0
        }
        $r = [RadminAutomationCore]::QueryStatus($proc.Id)
        if ($r.error -eq "MainWindow not found") {
            # Window was minimized/hidden in tray before initialization. Bring it up and retry.
            $exePath = "C:\Program Files (x86)\Radmin VPN\RvRvpnGui.exe"
            if (Test-Path $exePath) {
                Start-Process $exePath -ArgumentList "/show"
                Start-Sleep -Milliseconds 600
                $r = [RadminAutomationCore]::QueryStatus($proc.Id)
            }
        }
        $known = @()
        Get-Item -Path "HKCU:\Software\Famatech\Radmin VPN\ui\networks\*" -ErrorAction SilentlyContinue | ForEach-Object { $known += $_.PSChildName }
        foreach ($net in $r.networks) {
            if ($known -notcontains $net) {
                $known += $net
            }
        }
        [PSCustomObject]@{
            installed = $r.installed
            running = $r.running
            alias = $r.alias
            ip = $r.ip
            status = $r.status
            networks = $r.networks
            knownNetworks = $known
            error = $r.error
        } | ConvertTo-Json -Compress
    }
    "join" {
        if (-not $proc) {
            Start-Process "C:\Program Files (x86)\Radmin VPN\RvRvpnGui.exe" -ArgumentList "/show"
            Start-Sleep -Seconds 2
            $proc = Get-Process RvRvpnGui -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if (-not $proc) {
            [PSCustomObject]@{ ok = $false; message = "Radmin VPN não pôde ser iniciado." } | ConvertTo-Json -Compress
            exit 0
        }
        $res = [RadminAutomationCore]::Join($proc.Id, $Name, $Password)
        [PSCustomObject]@{ ok = $res.ok; message = $res.message } | ConvertTo-Json -Compress
    }
    "create" {
        if (-not $proc) {
            Start-Process "C:\Program Files (x86)\Radmin VPN\RvRvpnGui.exe" -ArgumentList "/show"
            Start-Sleep -Seconds 2
            $proc = Get-Process RvRvpnGui -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if (-not $proc) {
            [PSCustomObject]@{ ok = $false; message = "Radmin VPN não pôde ser iniciado." } | ConvertTo-Json -Compress
            exit 0
        }
        $res = [RadminAutomationCore]::Create($proc.Id, $Name, $Password)
        [PSCustomObject]@{ ok = $res.ok; message = $res.message } | ConvertTo-Json -Compress
    }
    "leave" {
        if (-not $proc) {
            [PSCustomObject]@{ ok = $false; message = "Radmin VPN não está em execução." } | ConvertTo-Json -Compress
            exit 0
        }
        $res = [RadminAutomationCore]::Leave($proc.Id, $Name)
        [PSCustomObject]@{ ok = $res.ok; message = $res.message } | ConvertTo-Json -Compress
    }
    "show" {
        Start-Process "C:\Program Files (x86)\Radmin VPN\RvRvpnGui.exe" -ArgumentList "/show"
        [PSCustomObject]@{ ok = $true; message = "Radmin VPN exibido." } | ConvertTo-Json -Compress
    }
}
