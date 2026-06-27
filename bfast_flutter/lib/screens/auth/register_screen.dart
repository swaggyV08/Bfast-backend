import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/auth_provider.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _phoneCtrl    = TextEditingController();
  final _nameCtrl     = TextEditingController();
  final _passcodeCtrl = TextEditingController();
  final _confirmCtrl  = TextEditingController();
  final _formKey      = GlobalKey<FormState>();

  @override
  void dispose() {
    _phoneCtrl.dispose();
    _nameCtrl.dispose();
    _passcodeCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);

    ref.listen<AuthState>(authProvider, (_, next) {
      if (next is AuthAuthenticated) {
        ref.read(authProvider.notifier).resetError();
        context.go('/home');
      }
    });

    final isLoading = authState is AuthLoading;

    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        title: const Text('Create Account'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.pop(),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 24),
                const Text(
                  'Create your account',
                  style: TextStyle(
                    color: AppTheme.textPrimary,
                    fontSize: 26,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Fast, secure tap-to-pay payments',
                  style: TextStyle(color: AppTheme.textSecondary, fontSize: 15),
                ),
                const SizedBox(height: 32),

                _buildField(
                  controller: _phoneCtrl,
                  label:      'Phone Number (+91XXXXXXXXXX)',
                  icon:       Icons.phone,
                  keyboardType: TextInputType.phone,
                  validator: (v) => v == null || v.trim().isEmpty ? 'Enter phone number' : null,
                ),
                const SizedBox(height: 14),

                _buildField(
                  controller: _nameCtrl,
                  label:      'Display Name',
                  icon:       Icons.person_outline,
                  validator:  (v) => (v?.trim().length ?? 0) < 2 ? 'At least 2 characters' : null,
                ),
                const SizedBox(height: 14),

                _buildField(
                  controller:   _passcodeCtrl,
                  label:        '4-Digit Passcode',
                  icon:         Icons.lock_outline,
                  obscure:      true,
                  maxLength:    4,
                  keyboardType: TextInputType.number,
                  letterSpacing: 8,
                  validator: (v) => v?.length != 4 ? 'Must be exactly 4 digits' : null,
                ),
                const SizedBox(height: 14),

                _buildField(
                  controller:   _confirmCtrl,
                  label:        'Confirm Passcode',
                  icon:         Icons.lock_outline,
                  obscure:      true,
                  maxLength:    4,
                  keyboardType: TextInputType.number,
                  letterSpacing: 8,
                  validator: (v) => v != _passcodeCtrl.text ? 'Passcodes do not match' : null,
                ),

                if (authState is AuthError) ...[
                  const SizedBox(height: 12),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    decoration: BoxDecoration(
                      color: AppTheme.error.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.error_outline, color: AppTheme.error, size: 16),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            authState.message,
                            style: const TextStyle(color: AppTheme.error, fontSize: 13),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],

                const SizedBox(height: 32),

                ElevatedButton(
                  onPressed: isLoading ? null : _onRegister,
                  child: isLoading
                      ? const SizedBox(
                          width: 24, height: 24,
                          child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                        )
                      : const Text('Create Account'),
                ),

                const SizedBox(height: 16),

                Center(
                  child: TextButton(
                    onPressed: () => context.pop(),
                    child: const Text(
                      'Already have an account? Login',
                      style: TextStyle(color: AppTheme.primary),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildField({
    required TextEditingController controller,
    required String label,
    required IconData icon,
    TextInputType keyboardType = TextInputType.text,
    bool obscure    = false,
    int? maxLength,
    double? letterSpacing,
    String? Function(String?)? validator,
  }) => TextFormField(
    controller:   controller,
    keyboardType: keyboardType,
    obscureText:  obscure,
    maxLength:    maxLength,
    validator:    validator,
    style: TextStyle(
      color:       AppTheme.textPrimary,
      letterSpacing: letterSpacing,
      fontSize:    letterSpacing != null ? 20 : 16,
    ),
    decoration: InputDecoration(
      labelText:   label,
      prefixIcon:  Icon(icon, color: AppTheme.textSecondary),
      counterText: '',
    ),
  );

  void _onRegister() {
    if (!_formKey.currentState!.validate()) return;
    ref.read(authProvider.notifier).register(
      _phoneCtrl.text.trim(),
      _passcodeCtrl.text.trim(),
      _confirmCtrl.text.trim(),
      _nameCtrl.text.trim(),
    );
  }
}
