class PaymentResult {
  final String  transactionId;
  final double  amount;
  final String  receiverName;
  final bool    success;
  final String? errorMessage;
  final DateTime timestamp;

  const PaymentResult({
    required this.transactionId,
    required this.amount,
    required this.receiverName,
    required this.success,
    this.errorMessage,
    required this.timestamp,
  });

  factory PaymentResult.fromJson(Map<String, dynamic> json) => PaymentResult(
    transactionId: json['transactionId'] as String? ?? '',
    amount:        (json['amount'] as num?)?.toDouble() ?? 0.0,
    receiverName:  json['receiverName'] as String? ?? '',
    success:       json['success'] as bool? ?? false,
    errorMessage:  json['message'] as String?,
    timestamp:     DateTime.tryParse(json['timestamp'] as String? ?? '') ?? DateTime.now(),
  );
}
