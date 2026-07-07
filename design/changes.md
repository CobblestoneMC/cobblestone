# Changes to design
These are changes I made to the overall structure/design since initial implementation

- Restructured projects into subfolders for better organization
- Got rid of the "DomainRegistry" -- instead, we will have Domain objects that we use directly. This gets rid of "domain ID" concepts. In practice, these should be implemented with hashCode and equals just pointing the internal ID used to identify the domain, which for Minecraft is the NamespacedKey (like `minecraft:overworld`)
- 