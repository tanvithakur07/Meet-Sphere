-- database/schema.sql
-- ============================================================
--  MeetSphere Database Schema
--  Run this file against your MySQL server before starting
-- ============================================================

CREATE DATABASE IF NOT EXISTS meetsphere_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE meetsphere_db;

-- -------------------------------------------------------
-- Table: users
-- Stores registered user credentials and profile info
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100)        NOT NULL,
    email        VARCHAR(150)        NOT NULL UNIQUE,
    password     VARCHAR(255)        NOT NULL,         -- BCrypt hashed
    avatar       VARCHAR(10)         DEFAULT NULL,     -- emoji avatar
    created_at   TIMESTAMP           DEFAULT CURRENT_TIMESTAMP
);

-- -------------------------------------------------------
-- Table: meetings
-- Stores meeting room metadata and unique codes
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS meetings (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_code VARCHAR(20)         NOT NULL UNIQUE,  -- e.g. abc-defg-hij
    title        VARCHAR(200)        DEFAULT 'MeetSphere Meeting',
    host_id      BIGINT              NOT NULL,
    is_active    BOOLEAN             DEFAULT TRUE,
    created_at   TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_meeting_host FOREIGN KEY (host_id) REFERENCES users(id)
);

-- -------------------------------------------------------
-- Table: meeting_participants
-- Tracks who joined which meeting and when
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_participants (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id   BIGINT              NOT NULL,
    user_id      BIGINT              NOT NULL,
    joined_at    TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    left_at      TIMESTAMP           NULL,
    CONSTRAINT fk_participant_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
    CONSTRAINT fk_participant_user    FOREIGN KEY (user_id)    REFERENCES users(id)
);

-- -------------------------------------------------------
-- Table: chat_messages
-- Persists in-meeting chat messages
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_messages (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id   BIGINT              NOT NULL,
    sender_id    BIGINT              NOT NULL,
    sender_name  VARCHAR(100)        NOT NULL,
    content      TEXT                NOT NULL,
    sent_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
    CONSTRAINT fk_chat_sender  FOREIGN KEY (sender_id)  REFERENCES users(id)
);

-- Indexes for faster queries
CREATE INDEX idx_meetings_code         ON meetings(meeting_code);
CREATE INDEX idx_participants_meeting  ON meeting_participants(meeting_id);
CREATE INDEX idx_chat_meeting          ON chat_messages(meeting_id);
