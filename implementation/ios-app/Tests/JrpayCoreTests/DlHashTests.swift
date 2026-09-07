import XCTest
@testable import JrpayCore

final class DlHashTests: XCTestCase {

    func testPaymentInitiationCanonicalKeyOrder() {
        let r = DlHash.hash(
            DlHash.PaymentInitiation(
                creditorIban: "NO9300000000000",
                debtorAccountId: "acc-1",
                amount: "100.00",
                currency: "NOK",
                reference: "INVOICE-1"
            ).toMap()
        )
        XCTAssertEqual(
            r.canonical,
            #"{"amount":"100.00","class":"payment-initiation","creditorIban":"NO9300000000000","currency":"NOK","debtorAccountId":"acc-1","reference":"INVOICE-1"}"#
        )
        XCTAssertNotNil(r.hex.range(of: #"^[0-9a-f]{64}$"#, options: .regularExpression))
    }

    func testPaymentInitiationOmitsOptionalFields() {
        let r = DlHash.hash(
            DlHash.PaymentInitiation(
                creditorIban: "NO9300000000000",
                debtorAccountId: "acc-1",
                amount: "100.00",
                currency: "NOK"
            ).toMap()
        )
        XCTAssertFalse(r.canonical.contains("reference"))
        XCTAssertFalse(r.canonical.contains("executionDate"))
    }

    func testMandateCreateIncludesCapFields() {
        let r = DlHash.hash(
            DlHash.MandateAction(
                action: "CREATE",
                creditorId: "creditor-1",
                debtorAccountId: "acc-1",
                capAmount: "500.00",
                capCurrency: "NOK"
            ).toMap()
        )
        XCTAssertEqual(
            r.canonical,
            #"{"action":"CREATE","capAmount":"500.00","capCurrency":"NOK","class":"mandate-action","creditorId":"creditor-1","debtorAccountId":"acc-1"}"#
        )
    }

    func testMandateTerminateOmitsCapFields() {
        let r = DlHash.hash(
            DlHash.MandateAction(
                action: "TERMINATE",
                creditorId: "creditor-1",
                debtorAccountId: "acc-1"
            ).toMap()
        )
        XCTAssertFalse(r.canonical.contains("capAmount"))
    }

    func testGdprActionFourKeys() {
        let r = DlHash.hash(
            DlHash.GdprAction(
                action: "EXPORT",
                customerId: "cust-1",
                notesHash: String(repeating: "a", count: 64)
            ).toMap()
        )
        XCTAssertEqual(
            r.canonical,
            #"{"action":"EXPORT","class":"gdpr-action","customerId":"cust-1","notesHash":"\#(String(repeating: "a", count: 64))"}"#
        )
    }

    func testNfcNormalisation() {
        let composed = "caf\u{00E9}"      // é precomposed
        let decomposed = "cafe\u{0301}"   // e + combining acute
        // Swift's `String` equality uses Unicode canonical equivalence, so
        // `composed == decomposed` is true even though the underlying byte
        // sequences differ. Compare UTF-8 bytes to prove the inputs really
        // are distinct before they hit sha256HexNfc.
        XCTAssertNotEqual(Array(composed.utf8), Array(decomposed.utf8))
        XCTAssertEqual(DlHash.sha256HexNfc(composed), DlHash.sha256HexNfc(decomposed))
    }

    func testEscapesControlsQuoteBackslashNewline() {
        let r = DlHash.hash(["k": "a\"b\\c\n"])
        XCTAssertEqual(r.canonical, #"{"k":"a\"b\\c\n"}"#)
    }

    func testArraysPreserveOrderObjectsSortByKey() {
        let r = DlHash.hash(
            [
                "arr": ["z", "a", "b"],
                "obj": ["y": "b", "x": "a"],
            ] as [String: Any]
        )
        XCTAssertEqual(r.canonical, #"{"arr":["z","a","b"],"obj":{"x":"a","y":"b"}}"#)
    }
}
