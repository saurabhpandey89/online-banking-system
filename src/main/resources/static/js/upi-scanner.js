let qrScanner = null;
let scannerStartTimer = null;
let isScannerStarting = false;
let isScannerRunning = false;
let shouldCancelStart = false;

// Active UPI Session State
let currentUpiSession = {
    vpa: null,
    verifiedName: null,
    payeeType: 'INDIVIDUAL',
    bankName: null,
    merchantCategory: null,
    merchantCode: null,
    suggestedRrn: null,
    fixedAmount: false,
    maxLimit: 100000.00,
    idempotencyKey: null
};

/* =========================================
   FORCE STOP CAMERA HARDWARE TRACKS
   ========================================= */
function forceStopCameraHardware() {
    try {
        const videos = document.querySelectorAll('video');
        videos.forEach(function(video) {
            if (video.srcObject && typeof video.srcObject.getTracks === 'function') {
                const tracks = video.srcObject.getTracks();
                tracks.forEach(function(track) {
                    try {
                        track.stop();
                    } catch (e) {
                        console.warn('Track stop error:', e);
                    }
                });
            }
            video.srcObject = null;
        });
    } catch (e) {
        console.error('forceStopCameraHardware error:', e);
    }

    const reader = document.getElementById('upi-reader');
    if (reader) {
        reader.innerHTML = '';
    }
}

/* =========================================
   OPEN SCANNER MODAL
   ========================================= */
function openUpiScanner() {
    const modalElement = document.getElementById('upiScannerModal');
    if (!modalElement) {
        alert('Scanner modal not found.');
        return;
    }

    shouldCancelStart = false;

    // Reset UI state
    hideLoading();
    const reader = document.getElementById('upi-reader');
    if (reader) {
        reader.innerHTML = '';
    }

    const modal = bootstrap.Modal.getOrCreateInstance(modalElement);
    modal.show();

    if (scannerStartTimer) {
        clearTimeout(scannerStartTimer);
        scannerStartTimer = null;
    }

    // Smooth delay before starting camera
    scannerStartTimer = setTimeout(() => {
        scannerStartTimer = null;
        if (!shouldCancelStart && modalElement.classList.contains('show')) {
            startQrScanner();
        }
    }, 250);
}

/* =========================================
   START CAMERA SCANNER
   ========================================= */
function startQrScanner() {
    if (isScannerStarting || isScannerRunning) {
        return;
    }

    const reader = document.getElementById('upi-reader');
    if (!reader) {
        return;
    }

    if (shouldCancelStart) {
        return;
    }

    isScannerStarting = true;

    try {
        qrScanner = new Html5Qrcode('upi-reader');
    } catch (err) {
        console.error('Html5Qrcode init error:', err);
        isScannerStarting = false;
        return;
    }

    const config = {
        fps: 10,
        qrbox: { width: 250, height: 250 },
        aspectRatio: 1.0
    };

    qrScanner.start(
        { facingMode: 'environment' },
        config,
        function(decodedText) {
            console.log('QR Scanned successfully:', decodedText);
            handleQrResult(decodedText);
        },
        function(errorMessage) {
            // Ignore frame scan errors
        }
    ).then(() => {
        isScannerStarting = false;
        isScannerRunning = true;

        if (shouldCancelStart) {
            stopQrScanner();
        }
    }).catch(function(error) {
        isScannerStarting = false;
        isScannerRunning = false;
        qrScanner = null;
        forceStopCameraHardware();

        if (!shouldCancelStart) {
            console.error('Camera error:', error);
            alert('Camera start nahi ho pa raha.\nKripya browser me camera permission allow karein.');
        }
    });
}

/* =========================================
   STOP QR SCANNER
   ========================================= */
async function stopQrScanner() {
    forceStopCameraHardware();

    if (qrScanner) {
        const scanner = qrScanner;
        qrScanner = null;

        try {
            if (typeof scanner.getState === 'function') {
                const state = scanner.getState();
                if (state === 2 || state === 3) {
                    await scanner.stop();
                }
            } else {
                await scanner.stop();
            }
        } catch (e) {
            console.warn('Scanner stop warning:', e);
        }

        try {
            scanner.clear();
        } catch (e) {
            console.warn('Scanner clear warning:', e);
        }
    }

    isScannerRunning = false;
    isScannerStarting = false;
}

/* =========================================
   CLOSE SCANNER
   ========================================= */
async function closeUpiScanner() {
    shouldCancelStart = true;

    if (scannerStartTimer) {
        clearTimeout(scannerStartTimer);
        scannerStartTimer = null;
    }

    await stopQrScanner();

    const modalElement = document.getElementById('upiScannerModal');
    if (modalElement) {
        const modal = bootstrap.Modal.getInstance(modalElement);
        if (modal) {
            modal.hide();
        }
    }

    setTimeout(() => {
        document.querySelectorAll('.modal-backdrop').forEach(b => b.remove());
        document.body.classList.remove('modal-open');
        document.body.style.removeProperty('padding-right');
    }, 150);
}

/* =========================================
   HANDLE SCANNED QR CODE (AUTHENTIC UPI)
   ========================================= */
function handleQrResult(qrData) {
    if (!qrData || qrData.trim().length === 0) {
        return;
    }

    // Instantly close scanner to free camera hardware
    closeUpiScanner();

    // Verify & validate payee via NPCI / PSP Gateway
    validateAndOpenUpiConfirmation(qrData.trim());
}

/* =========================================
   VALIDATE PAYEE VIA BACKEND NPCI / PSP API
   ========================================= */
function validateAndOpenUpiConfirmation(qrDataOrVpa) {
    showLoading('Verifying Payee with NPCI Switch...');

    fetch('/api/upi/validate', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ qrData: qrDataOrVpa })
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(err => { throw new Error(err.validationMessage || 'Failed to validate payee'); });
        }
        return response.json();
    })
    .then(data => {
        hideLoading();

        if (!data.valid) {
            alert('⚠️ UPI Validation Failed:\n' + (data.validationMessage || 'Invalid Payee UPI ID'));
            return;
        }

        // Store active session state
        currentUpiSession = {
            vpa: data.vpa,
            verifiedName: data.verifiedName,
            payeeType: data.payeeType || 'INDIVIDUAL',
            bankName: data.bankName,
            merchantCategory: data.merchantCategory,
            merchantCode: data.merchantCode,
            suggestedRrn: data.suggestedRrn || generateClientRrn(),
            fixedAmount: data.fixedAmount != null,
            maxLimit: data.maxAllowedAmount || 100000.00,
            idempotencyKey: 'IDEMP-UPI-' + Date.now() + '-' + Math.random().toString(36).substring(2, 9)
        };

        // Populate Confirmation Modal
        populateUpiConfirmationModal(data);

        // Open Confirmation Screen Modal
        const confirmModalEl = document.getElementById('upiPaymentConfirmModal');
        if (confirmModalEl) {
            const confirmModal = bootstrap.Modal.getOrCreateInstance(confirmModalEl);
            confirmModal.show();
        }
    })
    .catch(error => {
        hideLoading();
        console.error('UPI Validation Error:', error);
        alert('⚠️ UPI Error:\n' + (error.message || 'Payee verification failed. Please check network.'));
    });
}

/* =========================================
   POPULATE CONFIRMATION MODAL UI
   ========================================= */
function populateUpiConfirmationModal(data) {
    // Payee Avatar
    const avatarCharEl = document.getElementById('upiPayeeAvatarChar');
    if (avatarCharEl && data.verifiedName) {
        avatarCharEl.textContent = data.verifiedName.trim().charAt(0).toUpperCase();
    }

    // Name & VPA
    const nameEl = document.getElementById('upiConfirmPayeeName');
    if (nameEl) nameEl.textContent = data.verifiedName;

    const vpaEl = document.getElementById('upiConfirmPayeeVpa');
    if (vpaEl) vpaEl.textContent = data.vpa;

    const bankEl = document.getElementById('upiConfirmBankName');
    if (bankEl) bankEl.textContent = data.bankName || 'NPCI Interbank UPI Network';

    const catEl = document.getElementById('upiConfirmCategory');
    if (catEl) catEl.textContent = data.merchantCategory || (data.payeeType === 'MERCHANT' ? 'Merchant' : 'P2P Individual');

    const rrnEl = document.getElementById('upiConfirmRrn');
    if (rrnEl) rrnEl.textContent = currentUpiSession.suggestedRrn;

    // Amount & Fixed QR Bill Handling
    const amountInput = document.getElementById('upiConfirmAmount');
    const fixedBadge = document.getElementById('upiFixedAmountBadge');
    const maxLimitText = document.getElementById('upiMaxLimitText');

    if (maxLimitText) {
        maxLimitText.textContent = 'Max Limit: ₹' + Number(currentUpiSession.maxLimit).toLocaleString('en-IN', { minimumFractionDigits: 2 });
    }

    if (data.fixedAmount != null && Number(data.fixedAmount) > 0) {
        if (amountInput) {
            amountInput.value = Number(data.fixedAmount).toFixed(2);
            amountInput.readOnly = true;
        }
        if (fixedBadge) fixedBadge.classList.remove('d-none');
    } else {
        if (amountInput) {
            amountInput.value = '';
            amountInput.readOnly = false;
            amountInput.placeholder = '0.00';
        }
        if (fixedBadge) fixedBadge.classList.add('d-none');
    }

    // Remarks Note
    const noteInput = document.getElementById('upiConfirmNote');
    if (noteInput) {
        noteInput.value = data.transactionNote || '';
    }

    // Clear PIN and Error Alert
    const pinInput = document.getElementById('upiConfirmPin');
    if (pinInput) pinInput.value = '';

    const errorAlert = document.getElementById('upiConfirmErrorAlert');
    if (errorAlert) errorAlert.classList.add('d-none');

    // Reset Pay Button
    const payBtn = document.getElementById('upiSubmitPayBtn');
    if (payBtn) {
        payBtn.disabled = false;
        payBtn.innerHTML = '<i class="fa-solid fa-lock me-1"></i> Pay Securely';
    }
}

/* =========================================
   SUBMIT UPI PAYMENT WITH DUPLICATE SHIELD
   ========================================= */
function submitUpiPayment() {
    const errorAlert = document.getElementById('upiConfirmErrorAlert');
    const errorText = document.getElementById('upiConfirmErrorText');
    const payBtn = document.getElementById('upiSubmitPayBtn');

    function showError(msg) {
        if (errorAlert && errorText) {
            errorText.textContent = msg;
            errorAlert.classList.remove('d-none');
        } else {
            alert(msg);
        }
    }

    if (errorAlert) errorAlert.classList.add('d-none');

    // 1. Validate Amount
    const amountInput = document.getElementById('upiConfirmAmount');
    if (!amountInput || !amountInput.value) {
        showError('Please enter a valid amount.');
        return;
    }

    const amountNum = parseFloat(amountInput.value);
    if (isNaN(amountNum) || amountNum <= 0) {
        showError('Amount must be greater than zero.');
        return;
    }

    if (amountNum < 1.00) {
        showError('Minimum UPI payment is ₹1.00.');
        return;
    }

    if (amountNum > currentUpiSession.maxLimit) {
        showError('Amount exceeds UPI ceiling limit of ₹' + Number(currentUpiSession.maxLimit).toLocaleString('en-IN') + '.');
        return;
    }

    // 2. Validate Debiting Account
    const sourceAccSelect = document.getElementById('upiConfirmSourceAccount');
    if (!sourceAccSelect || !sourceAccSelect.value) {
        showError('Please select a debiting bank account.');
        return;
    }

    const sourceAccountNumber = sourceAccSelect.value;
    const pinInput = document.getElementById('upiConfirmPin');
    const pin = pinInput ? pinInput.value : '';

    const noteInput = document.getElementById('upiConfirmNote');
    const note = noteInput ? noteInput.value : '';

    // 3. Double-click & duplicate payment shield
    if (payBtn) {
        payBtn.disabled = true;
        payBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin me-2"></i>Processing with NPCI Switch...';
    }

    const payload = {
        sourceAccountNumber: sourceAccountNumber,
        payeeVpa: currentUpiSession.vpa,
        payeeName: currentUpiSession.verifiedName,
        amount: amountNum,
        pin: pin,
        description: note,
        npciRrn: currentUpiSession.suggestedRrn,
        idempotencyKey: currentUpiSession.idempotencyKey,
        merchantCode: currentUpiSession.merchantCode,
        fixedAmount: currentUpiSession.fixedAmount
    };

    fetch('/api/upi/pay', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-Idempotency-Key': currentUpiSession.idempotencyKey
        },
        body: JSON.stringify(payload)
    })
    .then(response => response.json())
    .then(data => {
        // Close Confirmation Modal
        const confirmModalEl = document.getElementById('upiPaymentConfirmModal');
        if (confirmModalEl) {
            const confirmModal = bootstrap.Modal.getInstance(confirmModalEl);
            if (confirmModal) confirmModal.hide();
        }

        // Open Receipt / Status Modal
        displayUpiReceipt(data);
    })
    .catch(error => {
        console.error('Payment Error:', error);
        if (payBtn) {
            payBtn.disabled = false;
            payBtn.innerHTML = '<i class="fa-solid fa-lock me-1"></i> Pay Securely';
        }
        showError(error.message || 'Payment communication error. Please check internet.');
    });
}

/* =========================================
   DISPLAY UPI RECEIPT & STATUS MODAL
   ========================================= */
function displayUpiReceipt(data) {
    const isSuccess = data.status === 'SUCCESS';

    const iconContainer = document.getElementById('upiStatusIconContainer');
    const icon = document.getElementById('upiStatusIcon');
    const title = document.getElementById('upiStatusTitle');
    const subtitle = document.getElementById('upiStatusSubtitle');
    const amountEl = document.getElementById('upiReceiptAmount');
    const payeeNameEl = document.getElementById('upiReceiptPayeeName');
    const payeeVpaEl = document.getElementById('upiReceiptPayeeVpa');
    const rrnEl = document.getElementById('upiReceiptRrn');
    const refEl = document.getElementById('upiReceiptRef');
    const debitedFromEl = document.getElementById('upiReceiptDebitedFrom');
    const balRow = document.getElementById('upiReceiptBalanceRow');
    const newBalEl = document.getElementById('upiReceiptNewBalance');
    const timestampEl = document.getElementById('upiReceiptTimestamp');
    const failBox = document.getElementById('upiReceiptFailBox');
    const failReasonEl = document.getElementById('upiReceiptFailReason');

    if (isSuccess) {
        if (iconContainer) iconContainer.style.background = 'rgba(16, 185, 129, 0.2)';
        if (icon) icon.className = 'fa-solid fa-circle-check text-success fs-1';
        if (title) title.textContent = 'Payment Successful!';
        if (subtitle) subtitle.textContent = data.message || 'Transferred securely via NPCI UPI Switch';
        if (failBox) failBox.classList.add('d-none');
        if (balRow) balRow.classList.remove('d-none');
    } else {
        if (iconContainer) iconContainer.style.background = 'rgba(239, 68, 68, 0.2)';
        if (icon) icon.className = 'fa-solid fa-circle-xmark text-danger fs-1';
        if (title) title.textContent = 'Payment Failed';
        if (subtitle) subtitle.textContent = 'NPCI Response: ' + (data.responseCode || 'Decline');
        if (failBox) failBox.classList.remove('d-none');
        if (failReasonEl) failReasonEl.textContent = '[' + (data.responseCode || 'FAIL') + '] ' + (data.message || 'Payment declined by bank');
        if (balRow) balRow.classList.add('d-none');
    }

    if (amountEl) amountEl.textContent = '₹ ' + (data.formattedAmount || Number(data.amount || 0).toFixed(2));
    if (payeeNameEl) payeeNameEl.textContent = data.payeeName || currentUpiSession.verifiedName || 'Payee';
    if (payeeVpaEl) payeeVpaEl.textContent = data.payeeVpa || currentUpiSession.vpa;
    if (rrnEl) rrnEl.textContent = data.npciRrn || currentUpiSession.suggestedRrn;
    if (refEl) refEl.textContent = data.transactionReference || 'N/A';
    if (debitedFromEl) debitedFromEl.textContent = data.sourceAccountNumber || 'Bank Account';
    if (newBalEl && data.sourceAccountBalance != null) {
        newBalEl.textContent = '₹ ' + Number(data.sourceAccountBalance).toLocaleString('en-IN', { minimumFractionDigits: 2 });
    }
    if (timestampEl) {
        timestampEl.textContent = new Date().toLocaleString('en-IN');
    }

    const receiptModalEl = document.getElementById('upiReceiptModal');
    if (receiptModalEl) {
        const receiptModal = bootstrap.Modal.getOrCreateInstance(receiptModalEl);
        receiptModal.show();
    }
}

/* =========================================
   UTILITIES
   ========================================= */
function toggleUpiPinVisibility() {
    const pinInput = document.getElementById('upiConfirmPin');
    const eyeIcon = document.getElementById('upiPinEyeIcon');
    if (pinInput && eyeIcon) {
        if (pinInput.type === 'password') {
            pinInput.type = 'text';
            eyeIcon.className = 'fa-solid fa-eye-slash';
        } else {
            pinInput.type = 'password';
            eyeIcon.className = 'fa-solid fa-eye';
        }
    }
}

function generateClientRrn() {
    const d = new Date();
    const day = Math.floor((d - new Date(d.getFullYear(), 0, 0)) / 1000 / 60 / 60 / 24);
    const prefix = '' + (d.getFullYear() % 10) + String(day).padStart(3, '0');
    const rand = Math.floor(10000000 + Math.random() * 90000000);
    return prefix + rand;
}

function showLoading(message) {
    const element = document.getElementById('scannerLoading');
    if (element) {
        element.textContent = message;
        element.classList.remove('d-none');
    }
}

function hideLoading() {
    const element = document.getElementById('scannerLoading');
    if (element) {
        element.classList.add('d-none');
    }
}

document.addEventListener('DOMContentLoaded', function() {
    const modal = document.getElementById('upiScannerModal');
    if (modal) {
        modal.addEventListener('hidden.bs.modal', function() {
            closeUpiScanner();
        });
    }
});
