      MeetSphere — Video Conferencing Web App 
A full-stack video conferencing application built using Spring Boot, WebRTC, and WebSocket. Users 
can create instant meeting rooms, join via code or shareable link, video/audio call peer-to-peer, and 
chat in real time. 
 
        Live Features 
•      JWT Authentication — Register and login with secure BCrypt-hashed passwords 
•       Peer-to-Peer Video Calling — WebRTC direct browser-to-browser video and audio 
•    Real-Time Chat — In-meeting text chat via WebSocket, persisted to database 
•    Meeting Codes — Create a room and share a xxxx-xxxx-xxxx code or direct link 
•       Screen Sharing — Share your screen with all participants 
•     Media Controls — Mute mic, toggle camera on/off during the call 
•    Participants Panel — See who is in the meeting live 
•        Meeting Timer — Live duration clock in the meeting header 
•        Responsive Layout — Works on different screen sizes 
 
     Tech Stack 
Layer Technology 
Frontend HTML5, CSS3, Vanilla JavaScript 
Backend Java 17, Spring Boot 3.2, Spring Security 
Real-Time WebRTC (peer-to-peer), WebSocket (signaling) 
Authentication JWT (JSON Web Tokens), BCrypt 
Database MySQL 8.0, Spring Data JPA, Hibernate 
Build Tool Maven 
 
   Project Structure 
MeetSphere/ 
│ 
├── backend/                          # Spring Boot application 
│   ├── pom.xml 
│   └── src/main/java/com/meetsphere/ 
│       ├── config/ 
│       │   ├── SecurityConfig.java   # JWT security + CORS 
│       │   └── WebSocketConfig.java  # Native WebSocket endpoint 
│       ├── controller/ 
│       │   ├── AuthController.java   # POST /api/auth/register, /login 
│       │   └── MeetingController.java# POST /api/meetings/create, /join, /leave 
│       ├── model/                    # JPA entities: User, Meeting, ChatMessage 
│       ├── repository/               # Spring Data JPA repositories 
│       ├── security/                 # JWT provider + filter 
│       ├── service/                  # Business logic 
│       └── websocket/ 
│           └── SignalingHandler.java # WebRTC signaling + chat routing 
│ 
├── frontend/                         # Static HTML/CSS/JS 
│   ├── pages/ 
│   │   ├── login.html 
│   │   ├── register.html 
│   │   ├── dashboard.html            # Create or join a meeting 
│   │   └── meeting.html              # The meeting room UI 
│   ├── css/ 
│   │   ├── main.css                  # Shared design tokens + components 
│   │   ├── auth.css 
│   │   ├── dashboard.css 
│   │   └── meeting.css               # Dark meeting room UI 
│   └── js/ 
│       ├── api.js                    # Centralized REST API client 
│       ├── auth.js                   # Login / register logic 
│       ├── dashboard.js              # Create / join meeting logic 
│       └── meeting.js                # WebRTC + WebSocket + media controls 
│ 
└── database/ 
    └── schema.sql                    # MySQL table definitions 
 
    How It Works 
Authentication Flow 
User registers → password BCrypt hashed → stored in MySQL 
User logs in   → Spring Security validates → JWT token returned 
All API calls  → JWT sent in Authorization: Bearer header 
                → JwtAuthenticationFilter validates on every request 
Video Call Flow 
User A creates meeting → unique code generated (e.g. ab3d-e7gh-ij2l) 
User A opens meeting.html → native WebSocket connects to Spring backend 
User A sends JOIN message → added to in-memory room map 
 
User B joins with same code → WebSocket connects → sends JOIN 
Server sends EXISTING_PEERS to User B (list of people already there) 
Server sends USER_JOINED to User A (new person arrived) 
 
User A sends WebRTC OFFER to User B  →  routed via Spring SignalingHandler 
User B sends WebRTC ANSWER to User A →  routed via Spring SignalingHandler 
Both exchange ICE candidates          →  routed via Spring SignalingHandler 
 
WebRTC connection established — video/audio flows DIRECTLY peer-to-peer 
                                 (never through the server) 
 
      Running Locally 
Prerequisites 
• Java 17+ 
• Maven 3.8+ 
• MySQL 8.0+ 
• A modern browser (Chrome or Edge) 
Step 1 — Database Setup 
sql 
mysql -u root -p 
source MeetSphere/database/schema.sql 
Step 2 — Configure Backend (secrets are NOT stored in the repo)
cd MeetSphere/backend
cp application-local.properties.example application-local.properties
Edit application-local.properties (git-ignored) and set:
spring.datasource.username=your_mysql_username
spring.datasource.password=your_mysql_password
app.jwt.secret=a-random-string-of-at-least-32-characters   (e.g. openssl rand -base64 48)
Or provide the same values as environment variables: DB_USERNAME, DB_PASSWORD, JWT_SECRET.
The backend refuses to start if the JWT secret is missing or shorter than 32 characters.
If you open the frontend from another host/IP (e.g. a phone on your LAN), add that origin to
app.cors.allowed-origins — it is used for both REST (CORS) and the WebSocket.
Step 3 — Run the application (Spring Boot serves the frontend AND the backend)
cd MeetSphere/backend
mvn clean spring-boot:run
Then open  http://localhost:8080  in your browser. That is the whole application — no Live Server needed.
Maven copies the ../frontend folder into the application's static content (see the <resources> block in pom.xml),
so pages, CSS, JavaScript and images are all served from http://localhost:8080 and the API from /api and /ws.
If you edit a file in frontend/ while the app is running, stop it and run the command again (or run: mvn process-resources).

Build one runnable JAR (frontend included):
mvn clean package
java -jar target\meetsphere-backend-1.0.0.jar

Step 4 — (optional) Old setup with Live Server
Opening the pages through VS Code Live Server (http://127.0.0.1:5500) still works: js/config.js detects it and talks to the
backend on port 8080. Normally you do not need this any more.
Step 5 — Test a Meeting 
1. Open http://localhost:8080 in Browser 1 → Register + Login as User A 
2. Click Start New Meeting → copy the meeting code 
3. Open http://localhost:8080 in Browser 2 (or Incognito) → Register + Login as User B 
4. Paste the code → click Join Meeting 
5. Both browsers should now see each other's video    
 
    API Endpoints 
Method Endpoint Auth Description 
POST /api/auth/register Public Register new user 
POST /api/auth/login Public Login, returns JWT 
POST /api/meetings/create JWT Create new meeting room 
POST /api/meetings/join/{code} JWT Join existing meeting 
POST /api/meetings/leave/{code} JWT Leave meeting
POST /api/meetings/end/{code} JWT Host only: end meeting for everyone
POST /api/meetings/{code}/invite JWT Host only: email the meeting invitation  { "emails": ["a@x.com"] }
POST /api/auth/change-password JWT Change the logged-in user's password  { currentPassword, newPassword, confirmPassword } 
GET /api/meetings/{code}/participants JWT Get active participants 
WS ws://localhost:8080/ws/signaling?token=<JWT> JWT (handshake) WebSocket signaling + chat 
 
          Database Schema 
users               — id, name, email, password (BCrypt), avatar, created_at 
meetings            — id, meeting_code, title, host_id (FK), is_active, created_at 
meeting_participants— id, meeting_id (FK), user_id (FK), joined_at, left_at 
chat_messages       — id, meeting_id (FK), sender_id (FK), sender_name, content, sent_at 
 
  Key Technical Decisions 
Why native WebSocket instead of SockJS? SockJS makes an HTTP preflight request to /info before 
upgrading. This was blocked by Spring Security with a 403, preventing any WebSocket connection 
from establishing. Native WebSocket (ws://) connects directly without any preflight. 
Why JWT instead of sessions? The backend is stateless — no HttpSession is created. JWT tokens 
carry all authentication state, making the API scalable and easy to test. 
Why peer-to-peer WebRTC? Video and audio streams flow directly between browsers using 
WebRTC. The Spring server only handles signaling (the initial handshake). This means video quality 
doesn't depend on server bandwidth and keeps hosting costs near zero. 
 
                        Author 
Built by Tanvi Thakur 
 
     License 
MIT License — free to use and modify. 

   Configuration & TURN
frontend/js/config.js holds the backend URLs (derived from the page host) and the ICE servers.
STUN alone fails on many networks — add a TURN server (e.g. coturn) in config.js for reliable calls.

   Mic / camera / screen-share status (MEDIA_STATE)
Each client broadcasts {audio: on|muted|unavailable, video: on|off|unavailable, screen: true|false} over the
WebSocket. The server sanitises it, relays it to the room and caches it, so people who join later see the
correct state. Camera, microphone and screen share each use their own WebRTC transceiver, so they are
switched with replaceTrack() — no renegotiation and no dropped connection.


   Email invitations (Invite by Email)
The host sees an "Invite" button in the meeting room. It opens a dialog where one or more email addresses
(up to 10 at a time) can be entered. The backend sends each person an HTML email with the meeting title, host
name, meeting code and a "Join Meeting" button that opens  <frontend URL>/pages/meeting.html?code=<code>.

How it works
Browser (invite.js) → POST /api/meetings/{code}/invite + JWT → MeetingController → MeetingService.inviteByEmail
→ EmailService → JavaMailSender → SMTP server → recipient.
The backend loads the meeting from the database (title, host, code are never taken from the request), checks that the
caller is the HOST, validates the addresses again, limits sending (10 per request, 30 per hour per user), and sends.

1) Get an SMTP login (Gmail example)
Google Account → Security → turn on 2-Step Verification → App passwords → create one named "MeetSphere".
Google shows a 16-character code: that is MAIL_PASSWORD (type it without spaces). Never use your normal Google password
and never share the App Password.

2) Set the environment variables on Windows (Command Prompt), then start the backend in the SAME window
   set MAIL_USERNAME=your.name@gmail.com
   set MAIL_PASSWORD=abcdefghijklmnop
   set APP_FRONTEND_URL=http://localhost:8080
   cd C:\projects\MeetSphere\backend
   mvn spring-boot:run
(Do not put quotes around the values in "set". PowerShell: $env:MAIL_USERNAME="your.name@gmail.com")
To keep them permanently: setx MAIL_USERNAME "your.name@gmail.com" and setx MAIL_PASSWORD "abcdefghijklmnop", then open a NEW terminal.
Alternative: put the values in backend/application-local.properties (git-ignored) — see application-local.properties.example.

Variables:  MAIL_USERNAME (required) · MAIL_PASSWORD (required) · MAIL_HOST (default smtp.gmail.com) · MAIL_PORT (default 587, STARTTLS)
            MAIL_FROM (optional sender; Gmail only allows your own address) · APP_FRONTEND_URL (public address of the app, default http://localhost:8080)
If MAIL_USERNAME is not set, the Invite dialog shows "Email invitations are not set up on the server yet".

3) Try it
Start MySQL + backend, open the frontend, sign in, start a meeting, click "Invite", enter another address you own, press Send,
and check that inbox (and spam). Clicking "Join Meeting" asks the guest to sign in, then opens the meeting.

Limitations: guests need a MeetSphere account; a "localhost" link only works on your own computer (set APP_FRONTEND_URL to a public
address to invite other people); no scheduling exists in MeetSphere, so the email has no date/time; invitations are not stored in the database.
Unit tests: mvn test  (email validation, rate limiting, email template).


   Windows quick start (database + secrets as environment variables, then run)
   set DB_USERNAME=root
   set DB_PASSWORD=your_mysql_password
   set JWT_SECRET=any-random-string-of-at-least-32-characters
   set MAIL_USERNAME=your.name@gmail.com
   set MAIL_PASSWORD=your16characterapppassword
   cd C:\projects\MeetSphere\backend
   mvn clean spring-boot:run
   → open http://localhost:8080
(No quotes around values. Use the SAME Command Prompt window for the set commands and mvn. MySQL must be running and the
database created from database/schema.sql. MAIL_* is optional — without it the app works, only the Invite button is disabled.)
Camera and microphone only work on http://localhost / 127.0.0.1 or on https:// — a plain http://192.168.x.x address is blocked by the browser.


   Change password
Dashboard → "Change password" (navbar). The dialog asks for the current password, the new password and a confirmation
(each with Show/Hide). Rules for the new password: 8-72 bytes, at least one letter and one number, different from the
current one. The same rules are checked in the browser (js/account.js) and again on the server (PasswordPolicy.java).

How it works
Browser → POST /api/auth/change-password { currentPassword, newPassword, confirmPassword } + JWT → AuthController →
AuthService.changePassword. The account is taken from the verified JWT — the request names no user. The current password is
checked with the project's BCrypt PasswordEncoder; the new one is hashed with the same encoder and saved in the existing
users.password column (no schema change). Errors: 400 invalid / mismatch / weak, 403 wrong current password,
401 not signed in, 429 too many attempts (10 per 15 minutes per user).

Old tokens stop working: every JWT now carries a "stamp" — an HMAC (keyed with your JWT secret) of the user's password hash.
HTTP requests and the WebSocket handshake both check it, so after a password change all earlier tokens (other browsers, a
stolen token) are rejected. The change-password response returns a fresh token, so the browser that changed the password stays
signed in. Calls already open in a running meeting (an established WebSocket) continue until they close.
One-time effect of upgrading: tokens issued BEFORE this feature have no stamp, so everyone signs in once more.
