package com.maciekhetman.cubetimer.data.remote.mapper

import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole

fun UserDto.toDomain(): User = User(
    id = id,
    email = email,
    displayName = displayName,
    emailVerified = emailVerified,
    userRole = UserRole.fromString(userRole),
    createdAt = createdAt
)
