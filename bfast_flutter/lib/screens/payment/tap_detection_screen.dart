import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/tap_provider.dart';
import '../../services/ble_service.dart';

/// The key payment detection screen.
///
/// SENDER view:
///   - Idle/scanning:  pure black + "Waiting for tap detection" (matches reference image)
///   - Receiver found: slide-up card showing receiver name
///   - Tap zone:       pulsing ring around receiver card
///   - Payment ready:  auto-navigate to payment entry
///
/// RECEIVER view:
///   - Armed:         pure black + "Waiting for tap detection"
///   - Tap detected:  toast fires instantly, screen shows confirmation
class TapDetectionScreen extends ConsumerStatefulWidget {
  final bool isSender;
  const TapDetectionScreen({super.key, required this.isSender});

  @override
  ConsumerState<TapDetectionScreen> createState() => _TapDetectionScreenState();
}

class _TapDetectionScreenState extends ConsumerState<TapDetectionScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _pulseController;
  late Animation<double>   _pulseAnimation;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      vsync:    this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 0.95, end: 1.05).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (widget.isSender) {
        ref.read(tapProvider.notifier).startAsSender();
      } else {
        ref.read(tapProvider.notifier).startAsReceiver();
      }
    });
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tapState = ref.watch(tapProvider);

    // Auto-navigate sender to payment entry when tap confirmed
    ref.listen<TapState>(tapProvider, (prev, next) {
      if (next.phase == TapPhase.paymentReady && widget.isSender) {
        context.push('/payment/entry');
      }
      if (next.phase == TapPhase.error) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content:          Text(next.errorMessage ?? 'Something went wrong'),
            backgroundColor:  AppTheme.error,
          ),
        );
      }
    });

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () {
            ref.read(tapProvider.notifier).reset();
            context.pop();
          },
        ),
        title: Text(
          widget.isSender ? 'Send Money' : 'Receive Money',
          style: const TextStyle(color: Colors.white, fontSize: 17),
        ),
      ),
      body: SafeArea(
        child: Stack(
          children: [
            // Main content based on state
            _buildBody(context, tapState),

            // Bottom receiver card (sender only)
            if (widget.isSender && tapState.nearbyReceivers.isNotEmpty)
              _buildReceiverCard(context, tapState),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context, TapState tapState) {
    switch (tapState.phase) {
      case TapPhase.tapDetected:
      case TapPhase.motionExchanged:
      case TapPhase.mutualConfirmation:
      case TapPhase.paymentReady:
        // Keep showing "Tap detected!" while GoRouter's async redirect
        // loads the payment screen — avoids a visible "Opening payment..." flash.
        return _buildTapDetectedView();

      case TapPhase.paymentCompleted:
        return _buildProcessingView();

      default:
        return _buildWaitingView(tapState);
    }
  }

  /// The reference-image look: black background, spinner + "Waiting for tap detection"
  Widget _buildWaitingView(TapState tapState) {
    String subtitle = '';
    if (widget.isSender) {
      if (tapState.phase == TapPhase.scanning) {
        subtitle = 'Looking for receiver…';
      } else if (tapState.phase == TapPhase.discovered ||
                 tapState.phase == TapPhase.sessionSetup) {
        subtitle = 'Receiver nearby — tap now!';
      } else if (tapState.phase == TapPhase.armed) {
        subtitle = 'Very close — tap to pay!';
      }
    } else {
      // Receiver view: always shows armed state
      subtitle = 'Hold still — tap your phone against the sender\'s';
    }

    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // Circular progress indicator — matches reference image exactly
          SizedBox(
            width: 28, height: 28,
            child: CircularProgressIndicator(
              strokeWidth: 1.5,
              color:       tapState.phase == TapPhase.armed
                  ? AppTheme.primary
                  : Colors.white,
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'Waiting for tap detection',
            style: TextStyle(
              color:      Colors.white,
              fontSize:   16,
              fontWeight: FontWeight.w400,
            ),
          ),
          if (subtitle.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              subtitle,
              style: const TextStyle(color: AppTheme.textSecondary, fontSize: 13),
            ),
          ],

          // Debug: phase + handshake step (debug builds only)
          if (!const bool.fromEnvironment('dart.vm.product')) ...[
            const SizedBox(height: 12),
            Text(
              '${tapState.phase.name}'
              '${tapState.hsStep.isNotEmpty ? "/${tapState.hsStep}" : ""}',
              style: const TextStyle(color: Colors.white24, fontSize: 10,
                  fontFamily: 'monospace'),
            ),
          ],

          // Proximity indicator dots
          if (tapState.phase == TapPhase.discovered ||
              tapState.phase == TapPhase.sessionSetup ||
              tapState.phase == TapPhase.armed) ...[
            const SizedBox(height: 40),
            _ProximityRing(phase: tapState.phase, animation: _pulseAnimation),
          ],
        ],
      ),
    );
  }

  Widget _buildReceiverCard(BuildContext context, TapState tapState) {
    return Positioned(
      left: 0, right: 0, bottom: 0,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        curve:    Curves.easeOut,
        decoration: const BoxDecoration(
          color: AppTheme.surface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 36, height: 4,
              decoration: BoxDecoration(
                color:        AppTheme.textSecondary.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 20),

            // If multiple receivers, show list; if one, show big card
            if (tapState.nearbyReceivers.length == 1)
              _SingleReceiverView(device: tapState.nearbyReceivers.first, phase: tapState.phase)
            else
              _MultiReceiverView(receivers: tapState.nearbyReceivers, phase: tapState.phase),
          ],
        ),
      ),
    );
  }

  Widget _buildTapDetectedView() => Center(
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Container(
          width: 80, height: 80,
          decoration: BoxDecoration(
            color:  AppTheme.primary.withValues(alpha: 0.15),
            shape:  BoxShape.circle,
            border: Border.all(color: AppTheme.primary, width: 2),
          ),
          child: const Icon(Icons.touch_app, color: AppTheme.primary, size: 40),
        ),
        const SizedBox(height: 20),
        const Text(
          'Tap detected!',
          style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 8),
        const Text(
          'Processing payment...',
          style: TextStyle(color: AppTheme.textSecondary, fontSize: 14),
        ),
      ],
    ),
  );

  Widget _buildPaymentReadyView() => const Center(
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        CircularProgressIndicator(color: AppTheme.primary),
        SizedBox(height: 20),
        Text(
          'Opening payment...',
          style: TextStyle(color: Colors.white, fontSize: 16),
        ),
      ],
    ),
  );

  Widget _buildProcessingView() => const Center(
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        CircularProgressIndicator(color: AppTheme.primary),
        SizedBox(height: 20),
        Text(
          'Processing payment',
          style: TextStyle(color: Colors.white, fontSize: 16),
        ),
      ],
    ),
  );
}

class _ProximityRing extends StatelessWidget {
  final TapPhase         phase;
  final Animation<double> animation;
  const _ProximityRing({required this.phase, required this.animation});

  @override
  Widget build(BuildContext context) {
    final color = phase == TapPhase.armed ? AppTheme.primary : AppTheme.textSecondary;
    return AnimatedBuilder(
      animation: animation,
      builder:  (_, __) => Transform.scale(
        scale: animation.value,
        child: Container(
          width: 80, height: 80,
          decoration: BoxDecoration(
            shape:  BoxShape.circle,
            border: Border.all(color: color.withValues(alpha: 0.5), width: 2),
          ),
          child: Center(
            child: Container(
              width: 50, height: 50,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color.withValues(alpha: 0.15),
              ),
              child: Icon(
                Icons.bluetooth,
                color: color,
                size: 24,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SingleReceiverView extends StatelessWidget {
  final BleDeviceInfo device;
  final TapPhase      phase;
  const _SingleReceiverView({required this.device, required this.phase});

  @override
  Widget build(BuildContext context) {
    final inTapZone = phase == TapPhase.armed;
    return Column(
      children: [
        CircleAvatar(
          radius: 30,
          backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
          child: Text(
            device.displayName.isNotEmpty ? device.displayName[0].toUpperCase() : '?',
            style: const TextStyle(color: AppTheme.primary, fontSize: 24, fontWeight: FontWeight.w700),
          ),
        ),
        const SizedBox(height: 12),
        Text(
          device.displayName,
          style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 4),
        Text(
          inTapZone ? 'Tap now to pay' : 'Move closer to tap',
          style: TextStyle(
            color:      inTapZone ? AppTheme.primary : AppTheme.textSecondary,
            fontSize:   13,
            fontWeight: inTapZone ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
        const SizedBox(height: 8),
        // RSSI bar
        _RssiBar(rssi: device.rssi),
        const SizedBox(height: 4),
      ],
    );
  }
}

class _MultiReceiverView extends StatelessWidget {
  final List<BleDeviceInfo> receivers;
  final TapPhase            phase;
  const _MultiReceiverView({required this.receivers, required this.phase});

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        '${receivers.length} devices nearby — tap the closest one',
        style: const TextStyle(color: AppTheme.textSecondary, fontSize: 13),
      ),
      const SizedBox(height: 12),
      ...receivers.take(4).map((d) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Row(
          children: [
            CircleAvatar(
              radius: 20,
              backgroundColor: AppTheme.primary.withValues(alpha: 0.15),
              child: Text(
                d.displayName.isNotEmpty ? d.displayName[0].toUpperCase() : '?',
                style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.w700),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(d.displayName, style: const TextStyle(color: Colors.white, fontSize: 14)),
                  _RssiBar(rssi: d.rssi),
                ],
              ),
            ),
            Text(
              '${d.rssi} dBm',
              style: const TextStyle(color: AppTheme.textSecondary, fontSize: 11),
            ),
          ],
        ),
      )),
    ],
  );
}

class _RssiBar extends StatelessWidget {
  final int rssi;
  const _RssiBar({required this.rssi});

  @override
  Widget build(BuildContext context) {
    // Map RSSI -80 to -30 onto 0-1 bar fill
    final fill = ((rssi + 80) / 50.0).clamp(0.0, 1.0);
    final color = rssi >= -45
        ? AppTheme.primary
        : rssi >= -55
            ? AppTheme.success
            : AppTheme.textSecondary;

    return SizedBox(
      height: 4,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(2),
        child: LinearProgressIndicator(
          value:           fill,
          backgroundColor: Colors.white10,
          valueColor:      AlwaysStoppedAnimation<Color>(color),
        ),
      ),
    );
  }
}
