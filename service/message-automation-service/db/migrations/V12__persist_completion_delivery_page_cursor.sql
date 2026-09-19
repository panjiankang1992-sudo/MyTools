ALTER TABLE automation_outbox
    ADD COLUMN delivery_page_cursor INT NOT NULL DEFAULT 0;

ALTER TABLE automation_outbox
    ADD CONSTRAINT chk_automation_outbox_delivery_page_cursor
        CHECK (delivery_page_cursor >= 0);
