class UserModel {
  final String id;
  final String phoneNumber;
  final String displayName;
  final double walletBalance;

  const UserModel({
    required this.id,
    required this.phoneNumber,
    required this.displayName,
    required this.walletBalance,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) => UserModel(
    id:            json['id'] as String? ?? '',
    phoneNumber:   json['phoneNumber'] as String? ?? '',
    displayName:   json['displayName'] as String? ?? '',
    walletBalance: (json['walletBalance'] as num?)?.toDouble() ?? 0.0,
  );

  Map<String, dynamic> toJson() => {
    'id':            id,
    'phoneNumber':   phoneNumber,
    'displayName':   displayName,
    'walletBalance': walletBalance,
  };
}
