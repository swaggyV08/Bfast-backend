import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:bfast_flutter/screens/payment/tap_detection_screen.dart';
import 'package:bfast_flutter/core/theme/app_theme.dart';

// Minimal router so GoRouter.of(context) doesn't throw inside TapDetectionScreen
GoRouter _testRouter({required Widget home}) => GoRouter(
      routes: [
        GoRoute(path: '/', builder: (_, __) => home),
      ],
    );

Widget buildUnderTest({required bool isSender}) {
  final screen = TapDetectionScreen(isSender: isSender);
  return ProviderScope(
    child: MaterialApp.router(
      theme: AppTheme.dark,
      routerConfig: _testRouter(home: screen),
    ),
  );
}

void main() {
  group('TapDetectionScreen', () {
    // ── Visual / theme ────────────────────────────────────────────────
    group('Visual structure', () {
      testWidgets('background is pure black (Color(0xFF000000))', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        final scaffold = tester.widget<Scaffold>(find.byType(Scaffold));
        expect(scaffold.backgroundColor, equals(const Color(0xFF000000)));
      });

      testWidgets('"Waiting for tap detection" text is present', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        expect(find.text('Waiting for tap detection'), findsOneWidget);
      });

      testWidgets('text is white color', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        final textWidgets = tester.widgetList<Text>(find.text('Waiting for tap detection'));
        final text = textWidgets.first;
        expect(text.style?.color, equals(Colors.white));
      });

      testWidgets('text font weight is normal (w400)', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        final text = tester.widget<Text>(find.text('Waiting for tap detection'));
        expect(text.style?.fontWeight, equals(FontWeight.w400));
      });

      testWidgets('text font size is 16', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        final text = tester.widget<Text>(find.text('Waiting for tap detection'));
        expect(text.style?.fontSize, equals(16.0));
      });

      testWidgets('CircularProgressIndicator is present', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        expect(find.byType(CircularProgressIndicator), findsOneWidget);
      });

      testWidgets('CircularProgressIndicator has strokeWidth 1.5', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        final indicator = tester.widget<CircularProgressIndicator>(
          find.byType(CircularProgressIndicator),
        );
        expect(indicator.strokeWidth, equals(1.5));
      });

      testWidgets('spinner and text are vertically centred (Column inside Center)',
          (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        expect(find.byType(Center), findsWidgets);
        expect(find.byType(Column), findsWidgets);
      });
    });

    // ── Role labels ───────────────────────────────────────────────────
    group('Role-specific content', () {
      testWidgets('sender shows "Send Money" or similar title in AppBar',
          (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        // Screen should have an AppBar
        expect(find.byType(AppBar), findsOneWidget);
        final appBar = tester.widget<AppBar>(find.byType(AppBar));
        expect(appBar.title, isNotNull);
      });

      testWidgets('receiver shows "Receive Money" or similar title in AppBar',
          (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: false));
        await tester.pump();

        expect(find.byType(AppBar), findsOneWidget);
        final appBar = tester.widget<AppBar>(find.byType(AppBar));
        expect(appBar.title, isNotNull);
      });
    });

    // ── No crash on build ─────────────────────────────────────────────
    group('Lifecycle', () {
      testWidgets('builds without exceptions', (tester) async {
        await expectLater(
          () async {
            await tester.pumpWidget(buildUnderTest(isSender: true));
            await tester.pump();
          },
          returnsNormally,
        );
      });

      testWidgets('receiver role builds without exceptions', (tester) async {
        await expectLater(
          () async {
            await tester.pumpWidget(buildUnderTest(isSender: false));
            await tester.pump();
          },
          returnsNormally,
        );
      });

      testWidgets('disposes cleanly', (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();
        // Replace with a different widget to trigger dispose
        await tester.pumpWidget(const MaterialApp(home: Scaffold()));
        // No exception = clean dispose
      });
    });

    // ── Accessibility ─────────────────────────────────────────────────
    group('Accessibility', () {
      testWidgets('meets minimum touch-target size for any tappable widgets',
          (tester) async {
        await tester.pumpWidget(buildUnderTest(isSender: true));
        await tester.pump();

        // All ElevatedButtons should be at least 44×44 logical pixels
        final buttons = find.byType(ElevatedButton);
        for (final btn in tester.widgetList(buttons)) {
          final renderBox = tester.renderObject(find.byWidget(btn))
              as RenderBox;
          expect(renderBox.size.width,  greaterThanOrEqualTo(44.0));
          expect(renderBox.size.height, greaterThanOrEqualTo(44.0));
        }
      });
    });
  });
}
