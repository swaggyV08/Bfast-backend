import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:fluttertoast/fluttertoast.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/auth_provider.dart';
import '../../providers/tap_provider.dart';
import '../../services/api_service.dart';
import '../../services/ble_service.dart';

// ── Wallet balance provider ───────────────────────────────────────────────

final _walletProvider = FutureProvider.autoDispose<double>((ref) async {
  final api = ref.read(apiServiceProvider);
  final res = await api.getWallet();
  if (res['success'] == true) {
    final data = res['data'] as Map<String, dynamic>? ?? {};
    final paise = data['balancePaise'] as num? ?? 0;
    return paise / 100.0;
  }
  return 0.0;
});

// ── Home screen ───────────────────────────────────────────────────────────

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      // Request all BLE permissions upfront so the user sees the system dialog
      // on the home screen, not mid-flow when they tap "Send Money".
      await BleService.requestBlePermissions();
      if (mounted) ref.read(tapProvider.notifier).startAsReceiver();
      _checkConnectivity();
    });
  }

  @override
  void dispose() {
    ref.read(tapProvider.notifier).reset();
    super.dispose();
  }

  Future<void> _checkConnectivity() async {
    final results = await Connectivity().checkConnectivity();
    if (results.contains(ConnectivityResult.none)) {
      _showToast('No internet connection', Colors.red);
    }
    final btState = await FlutterBluePlus.adapterState.first;
    if (btState != BluetoothAdapterState.on) {
      _showToast('Bluetooth is off — please enable it', Colors.red);
    }
  }

  void _showToast(String msg, Color color) {
    Fluttertoast.showToast(
      msg:             msg,
      toastLength:     Toast.LENGTH_LONG,
      gravity:         ToastGravity.BOTTOM,
      backgroundColor: color,
      textColor:       Colors.white,
    );
  }

  Future<void> _refreshWallet() async {
    ref.invalidate(_walletProvider);
    await ref.read(_walletProvider.future);
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);
    final user = authState is AuthAuthenticated ? authState.user : null;
    final tapState  = ref.watch(tapProvider);
    final walletAsync = ref.watch(_walletProvider);

    // Navigate to incoming payment screen when receiver detects mutual confirmation.
    // Also re-arm as receiver whenever the protocol resets to idle (e.g. after
    // the user returns from the sender flow).
    ref.listen<TapState>(tapProvider, (_, next) {
      if (next.phase == ProtocolPhase.mutualConfirmation && context.mounted) {
        context.push('/payment/incoming');
      }
      if (next.phase == ProtocolPhase.idle && mounted) {
        ref.read(tapProvider.notifier).startAsReceiver();
      }
    });

    return RefreshIndicator(
      onRefresh: _refreshWallet,
      color: AppTheme.primary,
      child: Scaffold(
        backgroundColor: AppTheme.background,
        appBar: AppBar(
          title: const Text('BFast',
              style: TextStyle(fontWeight: FontWeight.w700)),
          actions: [
            IconButton(
              icon: const Icon(Icons.sensors, color: AppTheme.textSecondary),
              tooltip: 'Sensor Test',
              onPressed: () => context.push('/sensor-test'),
            ),
            IconButton(
              icon: const Icon(Icons.logout, color: AppTheme.textSecondary),
              onPressed: () async {
                await ref.read(authProvider.notifier).logout();
                if (context.mounted) context.go('/login');
              },
            ),
          ],
        ),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              // ── Greeting ────────────────────────────────────────────────
              Text(
                'Hello, ${user?.displayName ?? 'User'}',
                style: const TextStyle(
                  color:      AppTheme.textPrimary,
                  fontSize:   24,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 4),
              _ReceiverStatusBadge(phase: tapState.phase),
              const SizedBox(height: 24),

              // ── Wallet balance card ──────────────────────────────────────
              _WalletCard(walletAsync: walletAsync, onRefresh: _refreshWallet),
              const SizedBox(height: 20),

              // ── Send Money ───────────────────────────────────────────────
              SizedBox(
                width: double.infinity,
                child: _ActionCard(
                  icon:     Icons.send_rounded,
                  label:    'Send Money',
                  subtitle: 'Tap phones to pay',
                  color:    AppTheme.primary,
                  onTap: () {
                    ref.read(tapProvider.notifier).reset();
                    context.push('/payment/sender');
                  },
                ),
              ),
              const SizedBox(height: 12),

              // ── Transaction History ──────────────────────────────────────
              SizedBox(
                width: double.infinity,
                child: _ActionCard(
                  icon:     Icons.history_rounded,
                  label:    'Transaction History',
                  subtitle: 'View past payments',
                  color:    const Color(0xFF7C4DFF),
                  onTap:    () => context.push('/transaction-history'),
                ),
              ),
              const SizedBox(height: 28),

              // ── Protocol state debug badge (only in debug) ───────────────
              if (!const bool.fromEnvironment('dart.vm.product'))
                _ProtocolStateBadge(phase: tapState.phase),

              // ── How it works ─────────────────────────────────────────────
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color:        AppTheme.surface,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Text('How it works',
                        style: TextStyle(
                          color:      AppTheme.textPrimary,
                          fontSize:   16,
                          fontWeight: FontWeight.w600,
                        )),
                    SizedBox(height: 12),
                    _TipRow(
                      icon: Icons.sensors,
                      text: 'You\'re always ready to receive — just keep the app open',
                    ),
                    SizedBox(height: 8),
                    _TipRow(
                      icon: Icons.send_rounded,
                      text: 'To send, tap "Send Money" and bring phones close',
                    ),
                    SizedBox(height: 8),
                    _TipRow(
                      icon: Icons.lock_outline,
                      text: 'A secure session is established before any tap',
                    ),
                    SizedBox(height: 8),
                    _TipRow(
                      icon: Icons.touch_app,
                      text: 'Both phones must detect the same tap to confirm',
                    ),
                    SizedBox(height: 8),
                    _TipRow(
                      icon: Icons.check_circle_outline,
                      text: 'Enter amount and confirm',
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Wallet card ───────────────────────────────────────────────────────────

class _WalletCard extends StatelessWidget {
  final AsyncValue<double> walletAsync;
  final VoidCallback onRefresh;

  const _WalletCard({required this.walletAsync, required this.onRefresh});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF1565C0), Color(0xFF42A5F5)],
          begin:  Alignment.topLeft,
          end:    Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('Wallet Balance',
                  style: TextStyle(color: Colors.white70, fontSize: 13)),
              GestureDetector(
                onTap: onRefresh,
                child: const Icon(Icons.refresh, color: Colors.white70, size: 18),
              ),
            ],
          ),
          const SizedBox(height: 8),
          walletAsync.when(
            data: (balance) => Text(
              '₹${balance.toStringAsFixed(2)}',
              style: const TextStyle(
                color:      Colors.white,
                fontSize:   32,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.5,
              ),
            ),
            loading: () => const SizedBox(
              height: 38,
              child: Center(
                child: SizedBox(
                  width: 20, height: 20,
                  child: CircularProgressIndicator(
                      color: Colors.white, strokeWidth: 2),
                ),
              ),
            ),
            error: (_, __) => const Text(
              '₹—',
              style: TextStyle(color: Colors.white70, fontSize: 32,
                  fontWeight: FontWeight.w700),
            ),
          ),
          const SizedBox(height: 4),
          const Text('Pull down to refresh',
              style: TextStyle(color: Colors.white38, fontSize: 11)),
        ],
      ),
    );
  }
}

// ── Action card ───────────────────────────────────────────────────────────

class _ActionCard extends StatelessWidget {
  final IconData icon;
  final String   label;
  final String   subtitle;
  final Color    color;
  final VoidCallback onTap;

  const _ActionCard({
    required this.icon,
    required this.label,
    required this.subtitle,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: onTap,
    child: Container(
      height: 80,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color:        AppTheme.surface,
        borderRadius: BorderRadius.circular(16),
        border:       Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color:        color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, color: color, size: 24),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment:  MainAxisAlignment.center,
              children: [
                Text(label,
                    style: const TextStyle(
                      color: AppTheme.textPrimary,
                      fontSize: 16, fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text(subtitle,
                    style: const TextStyle(
                      color: AppTheme.textSecondary, fontSize: 12)),
              ],
            ),
          ),
          const Icon(Icons.arrow_forward_ios,
              color: AppTheme.textSecondary, size: 14),
        ],
      ),
    ),
  );
}

// ── Receiver status badge ─────────────────────────────────────────────────

class _ReceiverStatusBadge extends StatelessWidget {
  final ProtocolPhase phase;
  const _ReceiverStatusBadge({required this.phase});

  @override
  Widget build(BuildContext context) {
    switch (phase) {
      case ProtocolPhase.advertising:
      case ProtocolPhase.armed:
      case ProtocolPhase.idle:
        return Row(children: [
          Container(width: 8, height: 8,
              decoration: const BoxDecoration(
                  color: Color(0xFF4CAF50), shape: BoxShape.circle)),
          const SizedBox(width: 6),
          const Text('Ready to receive payment',
              style: TextStyle(color: Color(0xFF4CAF50), fontSize: 13)),
        ]);
      case ProtocolPhase.connected:
      case ProtocolPhase.sessionSetup:
        return Row(children: [
          Container(width: 8, height: 8,
              decoration: const BoxDecoration(
                  color: Colors.orange, shape: BoxShape.circle)),
          const SizedBox(width: 6),
          const Text('Incoming payment…',
              style: TextStyle(color: Colors.orange, fontSize: 13)),
        ]);
      default:
        return const SizedBox.shrink();
    }
  }
}

// ── Protocol state badge (debug only) ────────────────────────────────────

class _ProtocolStateBadge extends StatelessWidget {
  final ProtocolPhase phase;
  const _ProtocolStateBadge({required this.phase});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: Colors.black26,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text('Protocol: ${phase.name}',
          style: const TextStyle(color: Colors.white54, fontSize: 11,
              fontFamily: 'monospace')),
    ),
  );
}

// ── Tip row ───────────────────────────────────────────────────────────────

class _TipRow extends StatelessWidget {
  final IconData icon;
  final String   text;
  const _TipRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(icon, color: AppTheme.primary, size: 16),
      const SizedBox(width: 10),
      Expanded(
        child: Text(text,
            style: const TextStyle(
                color: AppTheme.textSecondary, fontSize: 13)),
      ),
    ],
  );
}
