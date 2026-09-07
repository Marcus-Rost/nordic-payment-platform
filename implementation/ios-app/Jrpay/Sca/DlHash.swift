// DlHash now lives in the JrpayCore SPM target so it can be built and tested
// on Linux (where CryptoKit is unavailable). Re-export it so existing iOS
// views keep using `DlHash.PaymentInitiation(...)` etc. without an explicit
// `import JrpayCore` in every file.

@_exported import JrpayCore
