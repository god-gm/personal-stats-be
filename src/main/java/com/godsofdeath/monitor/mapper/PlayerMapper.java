package com.godsofdeath.monitor.mapper;

import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.internal.PlayerDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PlayerMapper {

    @Mapping(target = "enabled", expression = "java(\"Y\".equals(doc.getEnabled()))")
    PlayerDTO toDTO(PlayerDocument doc);
}
