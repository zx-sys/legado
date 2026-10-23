-- Legado 备份后端 MySQL 初始化脚本
-- 数据库：legado
-- 全部使用 IF NOT EXISTS，可重复执行，不会删除已有数据。

CREATE DATABASE IF NOT EXISTS `legado`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `legado`;

CREATE TABLE IF NOT EXISTS `users` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `phone` VARCHAR(32) NOT NULL,
    `nickname` VARCHAR(64) NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `ix_users_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `backups` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `user_id` INT NOT NULL,
    `device_name` VARCHAR(128) NOT NULL,
    `file_name` VARCHAR(255) NOT NULL,
    `trigger` VARCHAR(16) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `sha256` VARCHAR(64) NOT NULL,
    `size_bytes` BIGINT NOT NULL,
    `item_count` INT NOT NULL,
    `error` TEXT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `ix_backups_user_id` (`user_id`),
    KEY `ix_backups_created_at` (`created_at`),
    KEY `ix_backups_user_device_created` (`user_id`, `device_name`, `created_at`),
    KEY `ix_backups_user_sha` (`user_id`, `sha256`),
    CONSTRAINT `fk_backups_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `backup_items` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `backup_id` INT NOT NULL,
    `path` VARCHAR(512) NOT NULL,
    `content_type` VARCHAR(128) NOT NULL,
    `storage` VARCHAR(16) NOT NULL,
    `content` LONGTEXT NULL,
    `object_key` VARCHAR(512) NULL,
    `local_path` VARCHAR(512) NULL,
    `size_bytes` BIGINT NOT NULL,
    `sha256` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_backup_item_path` (`backup_id`, `path`),
    KEY `ix_backup_items_backup_id` (`backup_id`),
    CONSTRAINT `fk_backup_items_backup`
        FOREIGN KEY (`backup_id`) REFERENCES `backups` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `backup_records` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `backup_id` INT NOT NULL,
    `user_id` INT NOT NULL,
    `dataset` VARCHAR(64) NOT NULL,
    `external_id` VARCHAR(512) NULL,
    `payload` JSON NULL,
    PRIMARY KEY (`id`),
    KEY `ix_backup_records_backup_id` (`backup_id`),
    KEY `ix_backup_records_user_id` (`user_id`),
    KEY `ix_backup_records_dataset` (`backup_id`, `dataset`),
    CONSTRAINT `fk_backup_records_backup`
        FOREIGN KEY (`backup_id`) REFERENCES `backups` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_backup_records_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
