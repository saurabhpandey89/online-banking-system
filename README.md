# 🏦 Apex Horizon Bank - Online Banking System

> **Beginner-Friendly Project Guide & Architecture Documentation**  
> *Ek beginner ke liye poori project ki aasaan guide (Architecture, Folder Structure, aur Kaam karne ka tareeka)*

---

## 📌 1. Project Overview (Yeh Project Kya Hai?)

**Apex Horizon Bank** ek modern digital online banking web application hai jo **Java 17/25** aur **Spring Boot 3.3.2** par bani hai.

Is project me dono cheezein ek sath kaam karti hain:
1. **Web Browser UI (Thymeleaf + Bootstrap 5)**: User ke liye beautiful dark-themed banking dashboard jahan se woh login kar sakte hain, paise deposit/withdraw kar sakte hain, transfer kar sakte hain, aur camera se QR code scan kar sakte hain.
2. **REST Web APIs (JWT)**: Mobile apps aur external systems ke liye secure REST endpoints.

---

## 📂 2. Project Folder Structure (Kaunsi File Kahan Hai?)

```
onlinebankingsystem/
├── src/main/java/com/project/onlinebankingsystem/
│   ├── OnlinebankingsystemApplication.java  # 🚀 Main Entry Point (App yahan se start hoti hai)
│   │
│   ├── config/                           # 🔐 Security & JWT Settings
│   │   ├── SecurityConfig.java            # Kis page pe kaun ja sakta hai (Login/Dashboard/Admin)
│   │   ├── JwtUtils.java                  # API tokens generate aur verify karna
│   │   └── JwtAuthenticationFilter.java  # Har API request me token check karna
│   │
│   ├── controller/                       # 🌐 Web Pages & API Endpoints
│   │   ├── ViewController.java            # HTML web pages ko route karta hai (/, /login, /dashboard)
│   │   ├── TransactionController.java     # REST API: Deposit, Withdraw, Transfer
│   │   ├── AccountController.java         # REST API: Accounts list aur create karna
│   │   ├── UpiController.java             # REST API: UPI resolution & payments
│   │   ├── BeneficiaryController.java     # REST API: Payee manage karna
│   │   └── AdminController.java           # REST API: Bank Manager / Admin controls
│   │
│   ├── service/                          # ⚙️ Business Logic (Asli Calculation & Rules)
│   │   ├── UserService.java               # Naya user register karna, password hash karna
│   │   ├── AccountService.java            # 10-digit account no aur UPI ID create karna
│   │   ├── TransactionService.java        # Balance check, deduct, add, aur deadlock safe transfer
│   │   ├── UpiService.java                # UPI address verify aur QR details parse karna
│   │   └── BeneficiaryService.java        # Saved contacts ko add/delete karna
│   │
│   ├── model/                            # 💾 Database Tables (Entities)
│   │   ├── User.java                      # Users table (Profile, PAN, Aadhar, Phone)
│   │   ├── Account.java                   # Accounts table (Account number, Balance, Status)
│   │   ├── Transaction.java               # Transactions table (Har paise ke aane-jaane ka record)
│   │   └── Beneficiary.java               # Beneficiaries table (Saved payees)
│   │
│   └── repository/                       # 🔎 Database Queries (Spring Data JPA)
│       ├── UserRepository.java
│       ├── AccountRepository.java
│       ├── TransactionRepository.java
│       └── BeneficiaryRepository.java
│
└── src/main/resources/
    ├── application.properties               # DB connection & server settings
    ├── static/js/upi-scanner.js             # Camera QR Code Scanner script
    ├── static/css/style.css                 # Dark Glassmorphism banking theme
    └── templates/                           # HTML Web Pages
        ├── index.html                       # Home Page
        ├── login.html                       # Login Screen
        ├── register.html                    # Registration Screen
        ├── dashboard.html                   # User Banking Dashboard
        └── admin.html                       # Admin Management Panel
```

---

## 🔄 3. Kaam Kaise Karta Hai? (Step-by-Step Flow)

### 1. Registration (Naya Account Khulna)
1. User form bharta hai (`/register`).
2. `UserService` check karta hai ki username, email, phone, PAN, ya Aadhar duplicate toh nahi hai.
3. Password ko **BCrypt** se encrypt karke database me save kiya jata hai.
4. User ke liye automatic ek **10-digit Account Number** aur ek **UPI ID** (`username@obsbank`) create hota hai.

### 2. Login & Authentication
1. User username aur password daalta hai (`/login`).
2. Spring Security verify karta hai:
   - Sahi credentials par user ko dashboard (`/dashboard`) bhej deta hai.
   - Agar user ka role `ROLE_ADMIN` hai, toh use Admin Panel (`/admin`) ka access bhi milta hai.

### 3. Fund Transfer (Paise Ek Account Se Dusre Account Me Bhejna)
1. User `Transfer` tab me jaata hai aur Destination Account & Amount daalta hai.
2. `TransactionService` check karta hai:
   - Kya dono accounts `ACTIVE` hain?
   - Kya Sender ke account me balance kaafi hai?
3. **Deadlock Prevention Rule**:
   - Agar User A aur User B dono ek hi samay ek dusre ko paise transfer kar rahe hon, toh database lock na ho, iske liye code dono account numbers ko alphabetically sort karke lock karta hai.
4. Sender se paise minus hote hain, Receiver me add hote hain, aur ek naya `Transaction` record save ho jata hai.

### 4. UPI QR Scanner (Camera se Pay Karna)
1. Dashboard par **Scan QR** button dabane par webcam open hota hai.
2. QR scan hone par UPI ID extract hoti hai (`upi://pay?pa=...`).
3. System automatic payee ka naam aur account verify karke form me auto-fill kar deta hai.
4. **Close** dabate hi camera hardware turant band ho jata hai.

---

## ⚡ 4. Project Kaise Run Karein? (How to Run)

### Step 1: Terminal / Command Prompt Kholein
Project folder me jayein:
```bash
cd "Demo Project/onlinebankingsystem"
```

### Step 2: Maven Se Start Karein
Windows PowerShell ya CMD me run karein:
```powershell
.\mvnw.cmd spring-boot:run
```

### Step 3: Browser Me Kholein
- **Website URL**: [http://localhost:8080](http://localhost:8080)
- **Login Page**: [http://localhost:8080/login](http://localhost:8080/login)
- **Admin Console**: [http://localhost:8080/admin](http://localhost:8080/admin)
- **Default Administrator Credentials**:
  - **Full Name**: `Saurabh Pandey`
  - **Username**: `saurabh` *(or `admin` / `saurabhpandey`)*
  - **Password**: `admin123` *(or `admin` / `saurabh123`)*
  - **Role**: `ROLE_ADMIN`
- **H2 Database Console**: [http://localhost:8080/h2-console](http://localhost:8080/h2-console)
  - JDBC URL: `jdbc:h2:file:./data/bankdb`
  - User Name: `sa`
  - Password: *(blank)*

---

## ✅ 5. Testing & Verification

Project ke saare unit tests run karne ke liye:
```powershell
.\mvnw.cmd test
```
*(Saare 91 tests fully pass hote hain bina kisi error ke!)*
