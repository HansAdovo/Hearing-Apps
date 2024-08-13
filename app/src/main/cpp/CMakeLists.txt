cmake_minimum_required(VERSION 3.22.1)
project("hearingamp")

# Define the path to the Oboe directory
set(OBOE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../oboe)

# Print the Oboe directory path for debugging
message(STATUS "Oboe directory: ${OBOE_DIR}")

# Add Oboe as a subproject
add_subdirectory(${OBOE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/oboe)

# Create your library
add_library(${CMAKE_PROJECT_NAME} SHARED
        hearingamp.cpp)

# Link Oboe
target_link_libraries(${CMAKE_PROJECT_NAME}
        oboe
        android
        log)

# Include Oboe headers
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${OBOE_DIR}/include)

# Print Oboe target properties for debugging
get_target_property(OBOE_INCLUDE_DIRS oboe INTERFACE_INCLUDE_DIRECTORIES)
message(STATUS "Oboe include directories: ${OBOE_INCLUDE_DIRS}")