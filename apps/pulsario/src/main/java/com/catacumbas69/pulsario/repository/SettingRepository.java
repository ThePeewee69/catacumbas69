package com.catacumbas69.pulsario.repository;

import com.catacumbas69.pulsario.model.Setting;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Runtime settings changed from the UI.
 */
public interface SettingRepository extends MongoRepository<Setting, String> {
}
